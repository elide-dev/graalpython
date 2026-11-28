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

import java.math.BigInteger;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.runtime.object.PFactory;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Exact {@code int} values that do not fit GraalPy's tagged pointer
 * become native CPython {@code PyLongObject} structs when they escape to C. There is no Java
 * companion object and no handle table entry: the in-flight value stays a Java {@code long}, the
 * struct exists only while C holds a count on it.
 *
 * <p>
 * Struct layout (40 bytes, one size class): {@code ob_refcnt} @0, {@code ob_type} @8,
 * {@code long_value.lv_tag} @16, {@code long_value.ob_digit[4]} @24 (30-bit digits, as CPython on
 * 64-bit platforms). Up to 4 digits (120 bits) fit; larger values keep the stub path (Java
 * {@code PInt} plus native stub). A free block keeps its free-list link at @16.
 * </p>
 *
 * <p>
 * The allocator state is a region of the shared native value state block ({@link NativeComplex#S_LONG}) so that
 * C ({@code longobject.c}) uses the same free list without an upcall, and so that the borrowed stack
 * of {@link NativeComplex} also holds the counts of int arguments. A struct whose count reaches zero
 * is freed at once: nothing on the Java side keeps a pointer to it (the Java value is a copy).
 * Single-threaded: callers hold the GIL.
 * </p>
 */
public final class NativeLong {

    public static final boolean COUNTERS = NativeComplex.COUNTERS;

    public static final int BLOCK_SIZE = 40;
    public static final long OFF_REFCNT = 0;
    public static final long OFF_TYPE = 8;
    public static final long OFF_TAG = 16;
    public static final long OFF_DIGITS = 24;
    public static final int MAX_DIGITS = 4;
    private static final long OFF_LINK = 16;

    // region layout, relative to st + NativeComplex.S_LONG (must match GraalPyLongAllocState in longobject.c)
    public static final long L_FREE = 0;
    public static final long L_BUMP = 8;
    public static final long L_END = 16;
    public static final long L_TYPE = 24;
    public static final long L_ARENAS = 32;
    public static final long L_COUNTERS = 40;
    public static final int N_COUNTERS = 8;
    public static final long REGION_SIZE = L_COUNTERS + 8 * N_COUNTERS;

    public static final int C_ALLOC = 0;
    public static final int C_FREE = 1;
    public static final int C_JAVA_TO_C = 2;
    public static final int C_C_TO_JAVA = 3;
    public static final int C_C_ALLOC = 4;  // updated by C
    public static final int C_C_FREE = 5;   // updated by C
    public static final String[] COUNTER_NAMES = {"long_alloc", "long_free", "long_java_to_c", "long_c_to_java", "long_c_alloc", "long_c_free", "", ""};

    private static final long ARENA_SIZE = 64 * 1024;
    private static final long ARENA_HEADER = 16;

    private static final int SHIFT = 30;
    private static final long MASK = (1L << SHIFT) - 1;
    private static final long SIGN_ZERO = 1;
    private static final long SIGN_NEGATIVE = 2;

    private static final long[] JAVA_COUNTS = new long[N_COUNTERS];

    private NativeLong() {
    }

    private static void count(int idx) {
        if (COUNTERS) {
            JAVA_COUNTS[idx]++;
        }
    }

    private static long region(long st) {
        return st + NativeComplex.S_LONG;
    }

    @TruffleBoundary
    public static long[] snapshot(long st, boolean reset) {
        long[] res = new long[N_COUNTERS];
        for (int i = 0; i < N_COUNTERS; i++) {
            res[i] = JAVA_COUNTS[i] + (st != 0 ? UNSAFE.getLong(region(st) + L_COUNTERS + 8L * i) : 0);
            if (reset) {
                JAVA_COUNTS[i] = 0;
                if (st != 0) {
                    UNSAFE.putLong(region(st) + L_COUNTERS + 8L * i, 0);
                }
            }
        }
        return res;
    }

    /** Type pointer of {@code PyLong_Type}, or 0 before the C API is initialized. */
    public static long getLongType(long st) {
        return UNSAFE.getLong(region(st) + L_TYPE);
    }

    /** True if {@code p} (a non-handle PyObject pointer) is an exact native int struct. */
    public static boolean isNativeLong(long st, long p) {
        long type = UNSAFE.getLong(region(st) + L_TYPE);
        return type != 0 && UNSAFE.getLong(p + OFF_TYPE) == type;
    }

    // ---------------------------------------------------------------- allocation

    private static long alloc(long r) {
        long p = UNSAFE.getLong(r + L_FREE);
        if (p != 0) {
            UNSAFE.putLong(r + L_FREE, UNSAFE.getLong(p + OFF_LINK));
        } else {
            p = UNSAFE.getLong(r + L_BUMP);
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, p + BLOCK_SIZE > UNSAFE.getLong(r + L_END))) {
                p = newArena(r);
            } else {
                UNSAFE.putLong(r + L_BUMP, p + BLOCK_SIZE);
            }
        }
        count(C_ALLOC);
        return p;
    }

    @TruffleBoundary
    private static long newArena(long r) {
        long arena = UNSAFE.allocateMemory(ARENA_SIZE);
        UNSAFE.putLong(arena, UNSAFE.getLong(r + L_ARENAS));
        UNSAFE.putLong(r + L_ARENAS, arena);
        long p = arena + ARENA_HEADER;
        UNSAFE.putLong(r + L_BUMP, p + BLOCK_SIZE);
        UNSAFE.putLong(r + L_END, arena + ARENA_SIZE);
        return p;
    }

    private static void free(long r, long p) {
        UNSAFE.putLong(p + OFF_TYPE, 0);
        UNSAFE.putLong(p + OFF_LINK, UNSAFE.getLong(r + L_FREE));
        UNSAFE.putLong(r + L_FREE, p);
        count(C_FREE);
    }

    /** Drops one count; frees the struct when it reaches zero. */
    public static void decref(long st, long p) {
        long rc = UNSAFE.getLong(p + OFF_REFCNT) - 1;
        UNSAFE.putLong(p + OFF_REFCNT, rc);
        if (rc == 0) {
            free(region(st), p);
        }
    }

    // ---------------------------------------------------------------- Java <-> struct

    /**
     * Struct for a Java {@code long} handed to C, with one count. With {@code newRef} the count is
     * owned by C; otherwise it is held for the duration of the current C call (borrowed stack of
     * {@link NativeComplex}, released when the outermost downcall returns).
     */
    public static long toNative(long st, long v, boolean newRef) {
        long r = region(st);
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, UNSAFE.getLong(r + L_TYPE) == 0)) {
            return toNativeBeforeInit(v);
        }
        long p = alloc(r);
        // branch-free encoding; callers pass only values outside the int32 range (2 or 3 digits)
        assert v != (int) v;
        long neg = v >> 63;               // 0 or -1
        long mag = (v ^ neg) - neg;       // |v|; Long.MIN_VALUE stays 2**63 read unsigned
        long d2 = mag >>> (2 * SHIFT);    // 0..8
        long n = 2 + ((d2 + 15) >>> 4);   // 2, or 3 if d2 != 0
        UNSAFE.putLong(p + OFF_REFCNT, 1);
        UNSAFE.putLong(p + OFF_TYPE, UNSAFE.getLong(r + L_TYPE));
        UNSAFE.putLong(p + OFF_TAG, (n << 3) | (neg & SIGN_NEGATIVE));
        UNSAFE.putLong(p + OFF_DIGITS, (mag & MASK) | (((mag >>> SHIFT) & MASK) << 32));
        UNSAFE.putLong(p + OFF_DIGITS + 8, d2);
        if (!newRef) {
            NativeComplex.borrowPush(st, p);
        }
        count(C_JAVA_TO_C);
        return p;
    }

    /**
     * Before {@code graalpy_init_long_alloc} has run (C API initialization) the type pointer is not
     * known: the stub path (a {@code PInt} with a native stub). The count is owned by C, so the
     * promoted object stays alive (it may leak; only possible during initialization).
     */
    @TruffleBoundary
    private static long toNativeBeforeInit(long v) {
        return com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.PythonToNativeInternalNode.executeUncached(
                        PFactory.createInt(PythonLanguage.get(null), v), true);
    }

    /** Number of int structs currently allocated (carved minus free-list length); measurement only. */
    @TruffleBoundary
    public static long liveBlocks(long st) {
        long r = region(st);
        long carved = 0;
        long arena = UNSAFE.getLong(r + L_ARENAS);
        boolean first = true;
        while (arena != 0) {
            long end = first ? UNSAFE.getLong(r + L_BUMP) : arena + ARENA_SIZE;
            carved += (end - (arena + ARENA_HEADER)) / BLOCK_SIZE;
            first = false;
            arena = UNSAFE.getLong(arena);
        }
        long free = 0;
        for (long p = UNSAFE.getLong(r + L_FREE); p != 0; p = UNSAFE.getLong(p + OFF_LINK)) {
            free++;
        }
        return carved - free;
    }

    /**
     * Value of an exact int struct coming from C ({@code Long} or, above 64 bits, a {@code PInt});
     * with {@code transfer} drops C's count.
     */
    public static Object fromNative(PythonLanguage language, long st, long p, boolean transfer) {
        long tag = UNSAFE.getLong(p + OFF_TAG);
        long n = tag >>> 3;
        Object result;
        long d2 = UNSAFE.getInt(p + OFF_DIGITS + 8) & 0xFFFFFFFFL;
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.LIKELY_PROBABILITY, n <= 2 || (n == 3 && d2 < 8))) {
            long lo = UNSAFE.getLong(p + OFF_DIGITS);
            long mag = n == 0 ? 0 : n == 1 ? (lo & MASK) : ((lo & MASK) | ((lo >>> 32) << SHIFT) | (n == 3 ? d2 << (2 * SHIFT) : 0));
            result = (tag & 3) == SIGN_NEGATIVE ? -mag : mag;
        } else {
            result = readSlow(language, p, n, tag & 3);
        }
        if (transfer) {
            decref(st, p);
        }
        count(C_C_TO_JAVA);
        return result;
    }

    @TruffleBoundary
    private static Object readSlow(PythonLanguage language, long p, long n, long sign) {
        if (n == 3) {
            long lo = UNSAFE.getLong(p + OFF_DIGITS);
            long d2 = UNSAFE.getInt(p + OFF_DIGITS + 8) & 0xFFFFFFFFL;
            if (d2 < 8) {
                long mag = (lo & MASK) | ((lo >>> 32) << SHIFT) | (d2 << (2 * SHIFT));
                return sign == SIGN_NEGATIVE ? -mag : mag;
            }
            if (sign == SIGN_NEGATIVE && d2 == 8 && lo == 0) {
                return Long.MIN_VALUE;
            }
        }
        BigInteger mag = BigInteger.ZERO;
        for (long i = n - 1; i >= 0; i--) {
            mag = mag.shiftLeft(SHIFT).or(BigInteger.valueOf(UNSAFE.getInt(p + OFF_DIGITS + 4 * i) & 0xFFFFFFFFL));
        }
        return PFactory.createInt(language, sign == SIGN_NEGATIVE ? mag.negate() : mag);
    }
}
