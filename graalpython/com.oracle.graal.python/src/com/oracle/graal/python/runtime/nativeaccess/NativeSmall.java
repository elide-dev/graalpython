/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.runtime.nativeaccess;

import static com.oracle.graal.python.runtime.nativeaccess.NativeMemory.UNSAFE;

import com.oracle.graal.python.builtins.objects.cext.structs.CFields;
import com.oracle.graal.python.builtins.objects.cext.structs.CStructAccess;
import com.oracle.graal.python.builtins.objects.floats.PNativeFloat;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * The small-block pool of {@code PyObject_Malloc} ({@code obmalloc.c}),
 * shared with Java, and the native {@code float} structs built from it.
 *
 * <p>
 * A pool block is 64 bytes: a 16-byte header (requested size, {@link #POOL_MAGIC}) and up to 48 bytes
 * of payload; pointers handed out are payload pointers, so {@code PyObject_Free} (C) and
 * {@link #free} (Java) handle the same blocks. Free blocks keep the magic, hold the free-list link in
 * the size word and have the payload's {@code ob_type} cleared (a stale pointer kept by an in-flight
 * {@link PNativeFloat} is recognised by that). Arenas (64 KB) are never freed. Single-threaded:
 * callers hold the GIL.
 * </p>
 *
 * <p>
 * Floats: an exact {@code float} that GraalPy cannot tag (not exactly representable as a 32-bit
 * float) becomes a {@code PyFloatObject} struct from this pool when it escapes to C; a struct coming
 * back is read into a Java {@code double}. An instance of a native subclass of {@code float} that is
 * nothing but (type, {@code ob_fval}) ({@link #isFloatValueType}) is held in Java as a
 * {@link PNativeFloat} value copy instead of a {@code PythonAbstractNativeObject} with a weak
 * reference and lookup-table entry; it becomes a struct again when it escapes to C.
 * </p>
 */
public final class NativeSmall {

    public static final long POOL_MAGIC = 0x47505241504F4F4CL;
    public static final int BLOCK_SIZE = 64;
    public static final int MAX_PAYLOAD = BLOCK_SIZE - 16;
    private static final long ARENA_SIZE = 64 * 1024;
    private static final long ARENA_HEADER = 16;

    // region layout, relative to st + NativeComplex.S_SMALL (must match GraalPySmallState in obmalloc.c)
    public static final long R_FREE = 0;
    public static final long R_BUMP = 8;
    public static final long R_END = 16;
    public static final long R_ARENAS = 24;
    public static final long R_FLOAT_TYPE = 32;
    public static final long R_FLOAT_DEALLOC = 40;
    public static final long R_FLOAT_FREE = 48;
    public static final long R_COUNTERS = 56;
    public static final long REGION_SIZE = R_COUNTERS + 8 * 4;

    // PyFloatObject
    public static final long OFF_REFCNT = 0;
    public static final long OFF_TYPE = 8;
    public static final long OFF_FVAL = 16;
    public static final int FLOAT_SIZE = 24;
    /** Generation word of a pool block (last payload word), incremented on every free (C and Java). */
    public static final long OFF_GEN = 40;

    public static long generation(long p) {
        return UNSAFE.getLong(p + OFF_GEN);
    }

    private NativeSmall() {
    }

    private static long region(long st) {
        return st + NativeComplex.S_SMALL;
    }

    /** Pointer of {@code PyFloat_Type}, or 0 before the C API is initialized. */
    public static long getFloatType(long st) {
        return UNSAFE.getLong(region(st) + R_FLOAT_TYPE);
    }

    // ---------------------------------------------------------------- pool

    /** Payload pointer of a new pool block for {@code size} (at most {@link #MAX_PAYLOAD}) bytes. */
    public static long alloc(long st, long size) {
        long r = region(st);
        long b = UNSAFE.getLong(r + R_FREE);
        if (b != 0) {
            UNSAFE.putLong(r + R_FREE, UNSAFE.getLong(b));
        } else {
            b = UNSAFE.getLong(r + R_BUMP);
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, b + BLOCK_SIZE > UNSAFE.getLong(r + R_END))) {
                b = newArena(r);
            } else {
                UNSAFE.putLong(r + R_BUMP, b + BLOCK_SIZE);
            }
        }
        UNSAFE.putLong(b, size);
        UNSAFE.putLong(b + 8, POOL_MAGIC);
        return b + 16;
    }

    @TruffleBoundary
    private static long newArena(long r) {
        long arena = UNSAFE.allocateMemory(ARENA_SIZE);
        UNSAFE.putLong(arena, UNSAFE.getLong(r + R_ARENAS));
        UNSAFE.putLong(r + R_ARENAS, arena);
        long b = arena + ARENA_HEADER;
        UNSAFE.putLong(r + R_BUMP, b + BLOCK_SIZE);
        UNSAFE.putLong(r + R_END, arena + ARENA_SIZE);
        return b;
    }

    /** True if {@code p} is the payload pointer of a pool block (allocated or free). */
    public static boolean isPool(long p) {
        return UNSAFE.getLong(p - 8) == POOL_MAGIC;
    }

    /** Returns a pool block (payload pointer) to the free list. */
    public static void free(long st, long p) {
        long r = region(st);
        long b = p - 16;
        UNSAFE.putLong(p + OFF_TYPE, 0);
        UNSAFE.putLong(p + OFF_GEN, UNSAFE.getLong(p + OFF_GEN) + 1);
        UNSAFE.putLong(b, UNSAFE.getLong(r + R_FREE));
        UNSAFE.putLong(r + R_FREE, b);
    }

    /**
     * Drops one count of a float struct (exact or value-type subclass, see {@link #isFloatValueType})
     * and deallocates it at zero. Both have {@code tp_dealloc = object_dealloc} and
     * {@code tp_free = PyObject_Free}, so a pool block is freed here; any other block is deallocated
     * by C.
     */
    public static void decref(long st, long p) {
        long rc = UNSAFE.getLong(p + OFF_REFCNT) - 1;
        UNSAFE.putLong(p + OFF_REFCNT, rc);
        if (rc == 0) {
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.LIKELY_PROBABILITY, isPool(p))) {
                free(st, p);
            } else {
                deallocSlow(p);
            }
        }
    }

    @TruffleBoundary
    private static void deallocSlow(long p) {
        // count is zero: let C run the type's tp_dealloc (_Py_Dealloc)
        com.oracle.graal.python.builtins.objects.cext.capi.CExtNodes.XDecRefPointerNode.deallocNative(p);
    }

    // ---------------------------------------------------------------- floats

    /** True if {@code p} (a non-handle PyObject pointer) is an exact native float struct. */
    public static boolean isNativeFloat(long st, long p) {
        long type = getFloatType(st);
        return type != 0 && UNSAFE.getLong(p + OFF_TYPE) == type;
    }

    /**
     * Struct for a Java {@code double} handed to C, with one count; with {@code newRef} the count is
     * C's, otherwise it is on the borrowed stack until the outermost downcall returns. Callers check
     * {@link #getFloatType} first.
     */
    public static long floatToNative(long st, double d, boolean newRef) {
        long p = alloc(st, FLOAT_SIZE);
        UNSAFE.putLong(p + OFF_REFCNT, 1);
        UNSAFE.putLong(p + OFF_TYPE, getFloatType(st));
        UNSAFE.putDouble(p + OFF_FVAL, d);
        if (!newRef) {
            NativeComplex.borrowPush(st, p);
        }
        return p;
    }

    /** Value of a float struct (exact or value-type subclass); with {@code transfer} drops C's count. */
    public static double floatFromNative(long st, long p, boolean transfer) {
        double d = UNSAFE.getDouble(p + OFF_FVAL);
        if (transfer) {
            decref(st, p);
        }
        return d;
    }

    /**
     * Struct for an in-flight instance of a value-type float subclass. The struct the value was last
     * converted from or to is reused while it is the same incarnation (generation word unchanged, i.e.
     * not freed since) and alive with the same type and bits, so identity survives a round trip through
     * C; otherwise a new one is made.
     */
    public static long valueToNative(long st, PNativeFloat f, long typePtr, boolean newRef) {
        double d = f.getValue();
        long p = f.getStructHint();
        if (p != 0 && UNSAFE.getLong(p + OFF_GEN) == f.getStructGen() && UNSAFE.getLong(p + OFF_REFCNT) > 0 && UNSAFE.getLong(p + OFF_TYPE) == typePtr &&
                        UNSAFE.getLong(p + OFF_FVAL) == Double.doubleToRawLongBits(d)) {
            UNSAFE.putLong(p + OFF_REFCNT, UNSAFE.getLong(p + OFF_REFCNT) + 1);
        } else {
            p = alloc(st, FLOAT_SIZE);
            UNSAFE.putLong(p + OFF_REFCNT, 1);
            UNSAFE.putLong(p + OFF_TYPE, typePtr);
            UNSAFE.putDouble(p + OFF_FVAL, d);
            f.setStructHint(p, UNSAFE.getLong(p + OFF_GEN));
        }
        if (!newRef) {
            NativeComplex.borrowPush(st, p);
        }
        return p;
    }

    /** Keeps one count of {@code p} until the outermost downcall returns (borrowed stack). */
    public static void deferRelease(long st, long p) {
        NativeComplex.borrowPush(st, p);
    }

    private static final long TPFLAGS_MANAGED_WEAKREF = 1L << 3;
    private static final long TPFLAGS_MANAGED_DICT = 1L << 4;
    private static final long TPFLAGS_HEAPTYPE = 1L << 9;
    private static final long TPFLAGS_HAVE_GC = 1L << 14;

    /**
     * A native type whose instances are nothing but (type, {@code ob_fval}): a static subtype of
     * {@code float} with the size of {@code PyFloatObject}, no items, dict, weak list or GC, no
     * finalizer, and {@code float}'s deallocator and free function. Computed once per type (cached by
     * the caller).
     */
    @TruffleBoundary
    public static boolean isFloatValueType(long st, long t) {
        long r = region(st);
        long floatType = UNSAFE.getLong(r + R_FLOAT_TYPE);
        if (floatType == 0 || t == 0 || t == floatType) {
            return false;
        }
        if (CStructAccess.readLongField(t, CFields.PyTypeObject__tp_basicsize) != FLOAT_SIZE || CStructAccess.readLongField(t, CFields.PyTypeObject__tp_itemsize) != 0 ||
                        CStructAccess.readLongField(t, CFields.PyTypeObject__tp_dictoffset) != 0 || CStructAccess.readLongField(t, CFields.PyTypeObject__tp_weaklistoffset) != 0) {
            return false;
        }
        long flags = CStructAccess.readLongField(t, CFields.PyTypeObject__tp_flags);
        if ((flags & (TPFLAGS_MANAGED_WEAKREF | TPFLAGS_MANAGED_DICT | TPFLAGS_HEAPTYPE | TPFLAGS_HAVE_GC)) != 0) {
            return false;
        }
        if (CStructAccess.readPtrField(t, CFields.PyTypeObject__tp_dealloc) != UNSAFE.getLong(r + R_FLOAT_DEALLOC) ||
                        CStructAccess.readPtrField(t, CFields.PyTypeObject__tp_free) != UNSAFE.getLong(r + R_FLOAT_FREE) ||
                        CStructAccess.readPtrField(t, CFields.PyTypeObject__tp_finalize) != 0 || CStructAccess.readPtrField(t, CFields.PyTypeObject__tp_del) != 0) {
            return false;
        }
        long base = CStructAccess.readPtrField(t, CFields.PyTypeObject__tp_base);
        for (int i = 0; i < 16 && base != 0; i++) {
            if (base == floatType) {
                return true;
            }
            base = CStructAccess.readPtrField(base, CFields.PyTypeObject__tp_base);
        }
        return false;
    }
}
