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

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Exact {@code complex} objects as native, reference-counted CPython-layout structs.
 *
 * <p>
 * Struct layout (48 bytes): {@code ob_refcnt} @0, {@code ob_type} @8, {@code cval.real} @16,
 * {@code cval.imag} @24, serial @32, in-FIFO flag @40. A free block has serial 0 and keeps its
 * free-list link at @16.
 * Serials are globally increasing and never 0, so a stale (pointer, serial) pair never matches a
 * freed or reused block.
 * </p>
 *
 * <p>
 * All allocator state lives in one native block (the "state") so that C code can use the same free
 * list without an upcall (see {@code complexobject.c}). Arenas are allocated with the system allocator
 * once and are never returned, so reading the serial of any block that was ever handed out is safe.
 * Single-threaded: callers hold the GIL.
 * </p>
 *
 * <p>
 * Reference counts count list slots and C references only; an in-flight Java {@link PComplex} is a
 * value copy plus an unowned (pointer, serial) pair. A struct whose count drops to zero enters a FIFO
 * of {@link #FIFO_SIZE} (pointer, serial) entries; on eviction it is freed if its count is still zero
 * and its serial still matches.
 * </p>
 */
public final class NativeComplex {

    /** Counters (measurement only): constant false unless -Dgraalpy.complexCounters=true. */
    public static final boolean COUNTERS = Boolean.getBoolean("graalpy.complexCounters");

    public static final int BLOCK_SIZE = 48;
    public static final long OFF_REFCNT = 0;
    public static final long OFF_TYPE = 8;
    public static final long OFF_REAL = 16;
    public static final long OFF_IMAG = 24;
    public static final long OFF_SERIAL = 32;
    public static final long OFF_INFIFO = 40;
    private static final long OFF_LINK = 16;

    // state layout (must match GraalPyComplexAllocState in complexobject.c)
    public static final long S_FREE = 0;
    public static final long S_BUMP = 8;
    public static final long S_END = 16;
    public static final long S_SERIAL = 24;
    public static final long S_TYPE = 32;
    public static final long S_FIFO_POS = 40;
    public static final long S_FLAGS = 48;
    public static final long S_ARENAS = 56;
    public static final long S_COUNTERS = 64; // N_COUNTERS longs, updated by C code
    public static final int FIFO_SIZE = 64;   // power of two
    public static final long S_FIFO = S_COUNTERS + 8 * 16;
    // borrowed-reference stack: counted references held for the duration of a C call
    public static final long S_BSTACK = S_FIFO + 16L * FIFO_SIZE;
    public static final long S_BTOP = S_BSTACK + 8;
    public static final long S_BCAP = S_BTOP + 8;
    public static final long S_BDEPTH = S_BCAP + 8;   // downcall nesting depth
    // Allocator region of exact int structs (see NativeLong)
    public static final long S_LONG = S_BDEPTH + 8;
    public static final long S_SMALL = S_LONG + NativeLong.REGION_SIZE; // NativeSmall region
    public static final long STATE_SIZE = S_SMALL + NativeSmall.REGION_SIZE;

    private static final long ARENA_SIZE = 64 * 1024;
    private static final long ARENA_HEADER = 16;

    // counter indices
    public static final int C_ALLOC = 0;
    public static final int C_FREE = 1;
    public static final int C_INCREF = 2;
    public static final int C_DECREF = 3;
    public static final int C_STALE = 4;
    public static final int C_EVICT_FREED = 5;
    public static final int C_EVICT_KEPT = 6;
    public static final int C_C_TO_JAVA = 7;
    public static final int C_JAVA_TO_C = 8;
    public static final int C_REUSE = 9;      // store of an in-flight complex whose struct was still valid
    public static final int N_COUNTERS = 10;
    public static final String[] COUNTER_NAMES = {"native_alloc", "native_free", "incref", "decref", "stale_realloc", "fifo_evict_freed", "fifo_evict_kept",
                    "c_to_java", "java_to_c", "reuse"};

    private static final long[] JAVA_COUNTS = new long[N_COUNTERS];

    private NativeComplex() {
    }

    public static void count(int idx) {
        if (COUNTERS) {
            JAVA_COUNTS[idx]++;
        }
    }

    @TruffleBoundary
    public static long createState() {
        long st = UNSAFE.allocateMemory(STATE_SIZE);
        UNSAFE.setMemory(st, STATE_SIZE, (byte) 0);
        UNSAFE.putLong(st + S_FLAGS, COUNTERS ? 1 : 0);
        return st;
    }

    @TruffleBoundary
    public static long[] snapshot(long st, boolean reset) {
        long[] res = new long[N_COUNTERS];
        for (int i = 0; i < N_COUNTERS; i++) {
            res[i] = JAVA_COUNTS[i] + (st != 0 ? UNSAFE.getLong(st + S_COUNTERS + 8L * i) : 0);
            if (reset) {
                JAVA_COUNTS[i] = 0;
                if (st != 0) {
                    UNSAFE.putLong(st + S_COUNTERS + 8L * i, 0);
                }
            }
        }
        return res;
    }

    /** Number of blocks currently allocated (carved minus free-list length); debug only. */
    @TruffleBoundary
    public static long liveBlocks(long st) {
        long carved = 0;
        long arena = UNSAFE.getLong(st + S_ARENAS);
        boolean first = true;
        while (arena != 0) {
            long end = first ? UNSAFE.getLong(st + S_BUMP) : arena + ARENA_SIZE;
            carved += (end - (arena + ARENA_HEADER)) / BLOCK_SIZE;
            first = false;
            arena = UNSAFE.getLong(arena);
        }
        long free = 0;
        for (long p = UNSAFE.getLong(st + S_FREE); p != 0; p = UNSAFE.getLong(p + OFF_LINK)) {
            free++;
        }
        return carved - free;
    }

    // ---------------------------------------------------------------- allocation

    public static long alloc(long st, double real, double imag) {
        long p = UNSAFE.getLong(st + S_FREE);
        if (p != 0) {
            UNSAFE.putLong(st + S_FREE, UNSAFE.getLong(p + OFF_LINK));
        } else {
            p = UNSAFE.getLong(st + S_BUMP);
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, p + BLOCK_SIZE > UNSAFE.getLong(st + S_END))) {
                p = newArena(st);
            } else {
                UNSAFE.putLong(st + S_BUMP, p + BLOCK_SIZE);
            }
        }
        long serial = UNSAFE.getLong(st + S_SERIAL) + 1;
        UNSAFE.putLong(st + S_SERIAL, serial);
        UNSAFE.putLong(p + OFF_REFCNT, 0);
        UNSAFE.putLong(p + OFF_TYPE, UNSAFE.getLong(st + S_TYPE));
        UNSAFE.putDouble(p + OFF_REAL, real);
        UNSAFE.putDouble(p + OFF_IMAG, imag);
        UNSAFE.putLong(p + OFF_SERIAL, serial);
        UNSAFE.putLong(p + OFF_INFIFO, 0);
        count(C_ALLOC);
        return p;
    }

    @TruffleBoundary
    private static long newArena(long st) {
        long arena = UNSAFE.allocateMemory(ARENA_SIZE);
        UNSAFE.putLong(arena, UNSAFE.getLong(st + S_ARENAS));
        UNSAFE.putLong(st + S_ARENAS, arena);
        long p = arena + ARENA_HEADER;
        UNSAFE.putLong(st + S_BUMP, p + BLOCK_SIZE);
        UNSAFE.putLong(st + S_END, arena + ARENA_SIZE);
        return p;
    }

    private static void free(long st, long p) {
        UNSAFE.putLong(p + OFF_SERIAL, 0);
        UNSAFE.putLong(p + OFF_LINK, UNSAFE.getLong(st + S_FREE));
        UNSAFE.putLong(st + S_FREE, p);
        count(C_FREE);
    }

    // ---------------------------------------------------------------- reference counting

    public static void incref(long p) {
        UNSAFE.putLong(p + OFF_REFCNT, UNSAFE.getLong(p + OFF_REFCNT) + 1);
        count(C_INCREF);
    }

    public static void decref(long st, long p) {
        long rc = UNSAFE.getLong(p + OFF_REFCNT) - 1;
        UNSAFE.putLong(p + OFF_REFCNT, rc);
        count(C_DECREF);
        if (rc == 0) {
            enqueue(st, p);
        }
    }

    /**
     * Puts a zero-count struct into the deferred-free FIFO (unless it is already queued), evicting
     * the oldest entry; the evicted struct is freed if its count is still zero and its serial still
     * matches.
     */
    public static void enqueue(long st, long p) {
        if (UNSAFE.getLong(p + OFF_INFIFO) != 0) {
            return;
        }
        UNSAFE.putLong(p + OFF_INFIFO, 1);
        long pos = UNSAFE.getLong(st + S_FIFO_POS);
        long entry = st + S_FIFO + 16L * (pos & (FIFO_SIZE - 1));
        long old = UNSAFE.getLong(entry);
        if (old != 0) {
            if (UNSAFE.getLong(old + OFF_SERIAL) == UNSAFE.getLong(entry + 8)) {
                // same incarnation: it is leaving the FIFO
                UNSAFE.putLong(old + OFF_INFIFO, 0);
                if (UNSAFE.getLong(old + OFF_REFCNT) == 0) {
                    free(st, old);
                    count(C_EVICT_FREED);
                } else {
                    count(C_EVICT_KEPT);
                }
            } else {
                count(C_EVICT_KEPT);
            }
        }
        UNSAFE.putLong(entry, p);
        UNSAFE.putLong(entry + 8, UNSAFE.getLong(p + OFF_SERIAL));
        UNSAFE.putLong(st + S_FIFO_POS, pos + 1);
    }

    // ---------------------------------------------------------------- Java <-> struct

    public static boolean isValid(long p, long serial) {
        return p != 0 && UNSAFE.getLong(p + OFF_SERIAL) == serial;
    }

    /**
     * Returns a valid struct for the in-flight complex (allocating one if it has none or a stale
     * one) and records it in the Java object. Does not change the reference count. The argument is
     * only read and written field-wise, so a virtual {@link PComplex} stays virtual.
     */
    public static long materialize(long st, PComplex c) {
        long p = c.getNativePtr();
        if (p != 0) {
            if (UNSAFE.getLong(p + OFF_SERIAL) == c.getSerial()) {
                count(C_REUSE);
                return p;
            }
            count(C_STALE);
        }
        p = alloc(st, c.getReal(), c.getImag());
        c.setNative(p, UNSAFE.getLong(p + OFF_SERIAL));
        return p;
    }

    /** Builds an in-flight complex from a struct (no reference count change). */
    public static PComplex load(PythonLanguage language, long p) {
        return new PComplex(PythonBuiltinClassType.PComplex, PythonBuiltinClassType.PComplex.getInstanceShape(language),
                        UNSAFE.getDouble(p + OFF_REAL), UNSAFE.getDouble(p + OFF_IMAG), p, UNSAFE.getLong(p + OFF_SERIAL));
    }

    public static double readReal(long p) {
        return UNSAFE.getDouble(p + OFF_REAL);
    }

    public static double readImag(long p) {
        return UNSAFE.getDouble(p + OFF_IMAG);
    }

    public static long readSerial(long p) {
        return UNSAFE.getLong(p + OFF_SERIAL);
    }

    public static long readRefcnt(long p) {
        return UNSAFE.getLong(p + OFF_REFCNT);
    }

    public static void writeType(long p, long type) {
        UNSAFE.putLong(p + OFF_TYPE, type);
    }

    public static long readType(long p) {
        return UNSAFE.getLong(p + OFF_TYPE);
    }

    // ---------------------------------------------------------------- C API

    /**
     * Struct pointer for an exact complex handed to C. With {@code newRef} the caller (C) owns one
     * count; otherwise the count is taken for the duration of the current C call (borrowed stack,
     * released by {@link #borrowRelease} when the invoking downcall returns), so C can incref/decref
     * the argument without it reaching zero.
     */
    public static long toNative(long st, PComplex c, boolean newRef) {
        long p = materialize(st, c);
        UNSAFE.putLong(p + OFF_TYPE, UNSAFE.getLong(st + S_TYPE));
        incref(p);
        if (!newRef) {
            borrowPush(st, p);
        }
        count(C_JAVA_TO_C);
        return p;
    }

    /** Builds an in-flight complex from a struct coming from C; with {@code transfer} drops C's count. */
    public static PComplex fromNative(PythonLanguage language, long st, long p, boolean transfer) {
        PComplex c = load(language, p);
        if (transfer) {
            decref(st, p);
        }
        count(C_C_TO_JAVA);
        return c;
    }

    /** True if {@code p} (a non-handle PyObject pointer) is an exact native complex struct. */
    public static boolean isNativeComplex(long st, long p) {
        long type = UNSAFE.getLong(st + S_TYPE);
        return type != 0 && UNSAFE.getLong(p + OFF_TYPE) == type;
    }

    public static long getComplexType(long st) {
        return UNSAFE.getLong(st + S_TYPE);
    }

    static void borrowPush(long st, long p) {
        long top = UNSAFE.getLong(st + S_BTOP);
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, top >= UNSAFE.getLong(st + S_BCAP))) {
            growBorrowStack(st);
        }
        UNSAFE.putLong(UNSAFE.getLong(st + S_BSTACK) + 8 * top, p);
        UNSAFE.putLong(st + S_BTOP, top + 1);
    }

    @TruffleBoundary
    private static void growBorrowStack(long st) {
        long cap = UNSAFE.getLong(st + S_BCAP);
        long newCap = cap == 0 ? 256 : cap * 2;
        long old = UNSAFE.getLong(st + S_BSTACK);
        long arr = UNSAFE.allocateMemory(8 * newCap);
        if (old != 0) {
            UNSAFE.copyMemory(old, arr, 8 * cap);
            UNSAFE.freeMemory(old);
        }
        UNSAFE.putLong(st + S_BSTACK, arr);
        UNSAFE.putLong(st + S_BCAP, newCap);
    }

    /** Called by every generated downcall invoker before the native call. */
    public static void enterDowncall() {
        long st = com.oracle.graal.python.runtime.PythonContext.get(null).peekNativeComplexState();
        if (st != 0) {
            UNSAFE.putLong(st + S_BDEPTH, UNSAFE.getLong(st + S_BDEPTH) + 1);
        }
        com.oracle.graal.python.builtins.objects.cext.capi.CApiEpoch.javaRan();
    }

    /**
     * Called by every generated downcall invoker after the native call. Borrowed counts (arguments
     * converted before the call and borrowed results of upcalls made during it) are dropped when the
     * outermost downcall returns.
     */
    public static void exitDowncall() {
        long st = com.oracle.graal.python.runtime.PythonContext.get(null).peekNativeComplexState();
        if (st != 0) {
            long depth = UNSAFE.getLong(st + S_BDEPTH);
            if (depth > 0) {
                depth--;
                UNSAFE.putLong(st + S_BDEPTH, depth);
            }
            if (depth == 0) {
                borrowRelease(st, 0);
            }
        }
    }

    /** Depth of the borrowed stack at entry of a downcall (0 if there is no state). */
    public static long borrowMark(long st) {
        return st == 0 ? 0 : UNSAFE.getLong(st + S_BTOP);
    }

    /** Drops the borrowed counts taken since {@code mark}. */
    public static void borrowRelease(long st, long mark) {
        if (st == 0) {
            return;
        }
        long top = UNSAFE.getLong(st + S_BTOP);
        if (top > mark) {
            long arr = UNSAFE.getLong(st + S_BSTACK);
            long longType = NativeLong.getLongType(st);
            long complexType = UNSAFE.getLong(st + S_TYPE);
            while (top > mark) {
                top--;
                long p = UNSAFE.getLong(arr + 8 * top);
                long type = UNSAFE.getLong(p + OFF_TYPE);
                if (type == longType) {
                    NativeLong.decref(st, p);
                } else if (type == complexType) {
                    decref(st, p);
                } else {
                    // Float struct (exact or value-type subclass)
                    NativeSmall.decref(st, p);
                }
            }
            UNSAFE.putLong(st + S_BTOP, top);
        }
    }
}
