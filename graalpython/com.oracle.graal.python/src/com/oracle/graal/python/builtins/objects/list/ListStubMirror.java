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
package com.oracle.graal.python.builtins.objects.list;

import static com.oracle.graal.python.runtime.nativeaccess.NativeMemory.NULLPTR;

import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.HandlePointerConverter;
import com.oracle.graal.python.builtins.objects.cext.structs.CFields;
import com.oracle.graal.python.builtins.objects.cext.structs.CStructAccess;
import com.oracle.graal.python.builtins.objects.object.PythonObject;
import com.oracle.graal.python.runtime.sequence.storage.EmptySequenceStorage;
import com.oracle.graal.python.runtime.sequence.storage.NativeObjectSequenceStorage;
import com.oracle.graal.python.runtime.sequence.storage.SequenceStorage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native list size/items for C. A list's native stub is a {@code GraalPyVarObject}
 * ({@code ob_size}, {@code ob_item}), like a tuple's. The stub's fields are valid exactly when the
 * list's storage is {@link EmptySequenceStorage} ({@code 0, NULL}) or a
 * {@link NativeObjectSequenceStorage} attached to this list (its length and its native
 * {@code PyObject*} array). For any other storage the stub holds {@link #UNKNOWN} and {@code NULL},
 * and C asks Java. Java keeps the fields current through two funnels only: the list's storage
 * replacement ({@code PTupleListBase.setSequenceStorage}, only for lists that have a stub) and the
 * attached native storage's length/pointer setters ({@code NativeSequenceStorage.setNewLength/setPtr},
 * which write only while the storage is still the list's storage, so a replaced storage needs no
 * detaching). The element array is
 * the one GraalPy already keeps for lists whose {@code ob_item} C asked for; it holds counted
 * references and is freed by its {@code NativeStorageReference}.
 */
public final class ListStubMirror {
    /** Stub {@code ob_size} meaning "not mirrored: ask Java". */
    public static final long UNKNOWN = -1;

    private ListStubMirror() {
    }

    /** Called after the storage of a list that has a stub was set. */
    @TruffleBoundary
    public static void syncOutOfLine(PList list) {
        sync(list, HandlePointerConverter.pointerToStub(list.getNativePointer()));
    }

    /** Writes the stub fields from the list's current storage (stub creation, storage swap). */
    public static void sync(PList list, long realStubPointer) {
        SequenceStorage s = list.getSequenceStorage();
        long size;
        long items;
        if (s instanceof EmptySequenceStorage) {
            size = 0;
            items = NULLPTR;
        } else if (s instanceof NativeObjectSequenceStorage ns) {
            ns.attachMirror(list);
            size = ns.length();
            items = ns.getPtr();
        } else {
            size = UNKNOWN;
            items = NULLPTR;
        }
        CStructAccess.writeLongField(realStubPointer, CFields.GraalPyVarObject__ob_size, size);
        CStructAccess.writePtrField(realStubPointer, CFields.GraalPyVarObject__ob_item, items);
    }

    /** Attached native storage changed its length. */
    public static void writeSize(PythonObject owner, SequenceStorage storage, long length) {
        long p = owner.getNativePointer();
        if (p != PythonAbstractObject.UNINITIALIZED && ((PList) owner).getSequenceStorage() == storage) {
            CStructAccess.writeLongField(HandlePointerConverter.pointerToStub(p), CFields.GraalPyVarObject__ob_size, length);
        }
    }

    /** Attached native storage moved its element array. */
    public static void writeItems(PythonObject owner, SequenceStorage storage, long ptr) {
        long p = owner.getNativePointer();
        if (p != PythonAbstractObject.UNINITIALIZED && ((PList) owner).getSequenceStorage() == storage) {
            CStructAccess.writePtrField(HandlePointerConverter.pointerToStub(p), CFields.GraalPyVarObject__ob_item, ptr);
        }
    }
}
