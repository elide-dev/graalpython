/*
 * Copyright (c) 2018, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
#include "capi.h"

static int
_list_clear(PyListObject *a)
{
    int64_t i;
    PyObject **item;

    /* Because XDECREF can recursively invoke operations on
       this list, we make it empty first. */
    i = GraalPyPrivate_List_TruncateNativeStorage((PyObject *)a, &item);
    if (i > 0) {
        assert(item != NULL);
        while (--i >= 0) {
            Py_XDECREF(item[i]);
        }
        /* CPython calls 'PyMem_Free(item)' here but in our case, this will be
           done by the NativeStorageReference. Since we already set the length
           to zero, the items won't be decref'd again. */
    }
    /* Never fails; the return value can be ignored.
       Note that there is no guarantee that the list is actually empty
       at this point, because XDECREF may have populated it again! */
    return 0;
}

static int
list_traverse(PyListObject *o, visitproc visit, void *arg)
{
    int64_t size, i;
    PyObject **ob_item;

    /* In GraalPy, we only traverse the list if it has a native storage (which
     * is indicated by 'size > 0'). We don't traverse managed storages. For an
     * explanation, see 'dictobject.c: dict_traverse'.
     */
    if (points_to_py_handle_space(o)) {
        size = GraalPyPrivate_List_TryGetItems((PyObject *)o, &ob_item);
    } else {
        size = Py_SIZE(o);
        ob_item = o->ob_item;
    }

    for (i = size; --i >= 0; )
        Py_VISIT(ob_item[i]);
    return 0;
}

PyTypeObject PyList_Type = {
    PyVarObject_HEAD_INIT(&PyType_Type, 0)
    "list",
    sizeof(PyListObject),
    0,
    0,                                          /* tp_dealloc */ // GraalPy change: nulled
    0,                                          /* tp_vectorcall_offset */
    0,                                          /* tp_getattr */
    0,                                          /* tp_setattr */
    0,                                          /* tp_as_async */
    0,                                          /* tp_repr */ // GraalPy change: nulled
    0,                                          /* tp_as_number */
    0,                                          /* tp_as_sequence */ // GraalPy change: nulled
    0,                                          /* tp_as_mapping */ // GraalPy change: nulled
    PyObject_HashNotImplemented,                /* tp_hash */
    0,                                          /* tp_call */
    0,                                          /* tp_str */
    0,                                          /* tp_getattro */ // GraalPy change: nulled
    0,                                          /* tp_setattro */
    0,                                          /* tp_as_buffer */
    Py_TPFLAGS_DEFAULT | Py_TPFLAGS_HAVE_GC |
        Py_TPFLAGS_BASETYPE | Py_TPFLAGS_LIST_SUBCLASS |
        _Py_TPFLAGS_MATCH_SELF | Py_TPFLAGS_SEQUENCE,  /* tp_flags */
    0,                                          /* tp_doc */ // GraalPy change: nulled
    (traverseproc)list_traverse,                /* tp_traverse */
    (inquiry)_list_clear,                       /* tp_clear */
    0,                                          /* tp_richcompare */ // GraalPy change: nulled
    0,                                          /* tp_weaklistoffset */
    0,                                          /* tp_iter */ // GraalPy change: nulled
    0,                                          /* tp_iternext */
    0,                                          /* tp_methods */ // GraalPy change: nulled
    0,                                          /* tp_members */
    0,                                          /* tp_getset */
    0,                                          /* tp_base */
    0,                                          /* tp_dict */
    0,                                          /* tp_descr_get */
    0,                                          /* tp_descr_set */
    0,                                          /* tp_dictoffset */
    0,                                          /* tp_init */ // GraalPy change: nulled
    PyType_GenericAlloc,                        /* tp_alloc */
    PyType_GenericNew,                          /* tp_new */
    GraalPyPrivate_Object_GC_Del,              /* tp_free */ // GraalPy change: different function
    .tp_vectorcall = 0, // GraalPy change: nulled
};

void GraalPyList_SET_ITEM(PyObject* op, Py_ssize_t index, PyObject* value) {
    GraalPyList_ITEMS(op)[index] = value;
}

// deprecated alias for internal function, currently used in PyO3
PyAPI_FUNC(void) _PyList_SET_ITEM(PyObject* a, Py_ssize_t b, PyObject* c) {
    GraalPyList_SET_ITEM(a, b, c);
}

static inline int
valid_index(Py_ssize_t i, Py_ssize_t limit)
{
    /* The cast to size_t lets us use just a single comparison
       to check whether i is in the range: 0 <= i < limit.

       See:  Section 14.2 "Bounds Checking" in the Agner Fog
       optimization manual found at:
       https://www.agner.org/optimize/optimizing_cpp.pdf
    */
    return (size_t) i < (size_t) limit;
}

int
PyList_SetItem(PyObject *op, Py_ssize_t i,
               PyObject *newitem)
{
    PyObject **p;
    if (!PyList_Check(op)) {
        Py_XDECREF(newitem);
        PyErr_BadInternalCall();
        return -1;
    }
    if (!valid_index(i, Py_SIZE(op))) {
        Py_XDECREF(newitem);
        PyErr_SetString(PyExc_IndexError,
                        "list assignment index out of range");
        return -1;
    }
    // GraalPy change: avoid direct struct access
    p = GraalPyList_ITEMS(op) + i;
    Py_XSETREF(*p, newitem);
    return 0;
}

// GraalPy-additions

/*
 * A list's native stub is a GraalPyVarObject whose ob_size/ob_item Java keeps valid while the list's
 * storage is empty (0, NULL) or a native PyObject* array attached to the list (length, array); otherwise ob_size is
 * -1 and C asks Java (see ListStubMirror.java). Reads below never enter Java when the stub is valid.
 */
static inline GraalPyVarObject *
graalpy_list_stub(PyObject *op)
{
    /* Only a real stub (top bits 0b100) has memory: tagged ints (0b11x) and floats (0b101) are also in
       handle space but are immediates and must not be dereferenced (e.g. PyList_Size(23) must fail). */
    if (((uint64_t)(uintptr_t) op >> 61) == 4) {
        GraalPyVarObject *s = (GraalPyVarObject *) pointer_to_stub(op);
        PyTypeObject *t = ((PyObject *) s)->ob_type;
        if (t == &PyList_Type || PyType_FastSubclass(t, Py_TPFLAGS_LIST_SUBCLASS)) {
            return s;
        }
    }
    return NULL;
}

PyObject **
GraalPyList_ITEMS(PyObject *op)
{
    GraalPyVarObject *s = graalpy_list_stub(op);
    if (s != NULL && s->ob_item != NULL) {
        return s->ob_item;
    }
    return GraalPyPrivate_GET_PyListObject_ob_item(op);
}

Py_ssize_t
PyList_Size(PyObject *op)
{
    GraalPyVarObject *s = graalpy_list_stub(op);
    if (s != NULL && s->ob_size >= 0) {
        return s->ob_size;
    }
    return GraalPyPrivate_List_Size(op);
}

PyObject *
PyList_GetItem(PyObject *op, Py_ssize_t i)
{
    GraalPyVarObject *s = graalpy_list_stub(op);
    if (s != NULL) {
        if (s->ob_size < 0) {
            /* C reads an element: the list's storage becomes a native PyObject* array (as for ob_item) */
            GraalPyPrivate_GET_PyListObject_ob_item(op);
        }
        Py_ssize_t n = s->ob_size;
        if (n >= 0) {
            if (!valid_index(i, n)) {
                PyErr_SetString(PyExc_IndexError, "list index out of range");
                return NULL;
            }
            return s->ob_item[i];
        }
    }
    return GraalPyPrivate_List_GetItem(op, i);
}

/* PyList_Type's sq_length / mp_length: the stub's ob_size if valid, else the Java slot installed at type init */
static lenfunc graalpy_list_java_sq_length;
static lenfunc graalpy_list_java_mp_length;

static Py_ssize_t
graalpy_list_sq_length(PyObject *op)
{
    /* No type test, as CPython's list_length: only a real stub (tag 0b100 in the top bits; tagged ints/floats
       have no memory) is read; every stub has a zeroed ob_size slot (CApiTransitions), lists keep it valid or -1 */
    if (((uint64_t)(uintptr_t) op >> 61) == 4) {
        Py_ssize_t n = ((GraalPyVarObject *) pointer_to_stub(op))->ob_size;
        if (n >= 0) {
            return n;
        }
    }
    return graalpy_list_java_sq_length(op);
}

static Py_ssize_t
graalpy_list_mp_length(PyObject *op)
{
    /* No type test, as CPython's list_length: only a real stub (tag 0b100 in the top bits; tagged ints/floats
       have no memory) is read; every stub has a zeroed ob_size slot (CApiTransitions), lists keep it valid or -1 */
    if (((uint64_t)(uintptr_t) op >> 61) == 4) {
        Py_ssize_t n = ((GraalPyVarObject *) pointer_to_stub(op))->ob_size;
        if (n >= 0) {
            return n;
        }
    }
    return graalpy_list_java_mp_length(op);
}

/* ml_meth of list.clear (METH_NOARGS) as C sees it: an empty list whose stub is valid needs no work (as in CPython);
   everything else goes to Java through PyList_Clear */
static PyObject *
graalpy_list_clear_meth(PyObject *self, PyObject *Py_UNUSED(ignored))
{
    /* no type test, as CPython's list_clear (ml_meth is only valid with a list self); a real stub (top bits 0b100)
       always has a zeroed or valid ob_size slot (CApiTransitions) */
    if (((uint64_t)(uintptr_t) self >> 61) != 4 || ((GraalPyVarObject *) pointer_to_stub(self))->ob_size != 0) {
        if (PyList_Clear(self) < 0) {
            return NULL;
        }
    }
    return Py_None; /* immortal: no reference count change (Py_RETURN_NONE's incref is a no-op for it) */
}

Py_LOCAL_SYMBOL void
graalpy_init_list_slots(void)
{
    GraalPyPrivate_List_SetClearMeth((void *) graalpy_list_clear_meth);
    if (PyList_Type.tp_as_sequence != NULL && PyList_Type.tp_as_sequence->sq_length != NULL) {
        graalpy_list_java_sq_length = PyList_Type.tp_as_sequence->sq_length;
        PyList_Type.tp_as_sequence->sq_length = graalpy_list_sq_length;
    }
    if (PyList_Type.tp_as_mapping != NULL && PyList_Type.tp_as_mapping->mp_length != NULL) {
        graalpy_list_java_mp_length = PyList_Type.tp_as_mapping->mp_length;
        PyList_Type.tp_as_mapping->mp_length = graalpy_list_mp_length;
    }
}
