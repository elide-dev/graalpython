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
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * Exact {@code str} values cross into C as native CPython-layout compact unicode
 * objects ({@code PyASCIIObject} / {@code PyCompactUnicodeObject} with the characters inline), made
 * when C asks, with no Java wrapper, no stub and no handle-table entry. C's reference count owns
 * the struct: it is freed by {@code unicode_dealloc} in C (or by Java when Java drops the last
 * count). Strings created by C ({@code PyUnicode_New}, {@code PyUnicode_FromStringAndSize}, the
 * UTF-8 decoders) are born in the same layout and become Java strings only when Java reads them.
 *
 * <p>
 * Layout (CPython 3.13, 64-bit): {@code ob_refcnt} @0, {@code ob_type} @8, {@code length} @16,
 * {@code hash} @24, {@code state} @32 (32-bit bitfield: interned:2, kind:3, compact:1, ascii:1,
 * statically_allocated:1), 4 bytes of padding @36 that CPython never reads. ASCII strings: data @40.
 * Other strings: {@code utf8_length} @40, {@code utf8} @48, data @56. Data is followed by one NUL
 * code unit.
 * </p>
 *
 * <p>
 * The padding word @36 is ours: bit 31 marks a struct made by this scheme (by Java or by C); the
 * low bits hold 1 + the index of an entry of a small ring that maps the struct to a Java object:
 * the Java string it was copied from, or the str object ({@code PString}) Java made when it read
 * the struct. The ring keeps the last {@link #RING_SIZE} crossings. An entry is valid for a struct
 * only if it still names that struct's address; a struct is freed and re-made only with a fresh
 * word, so a stale entry is never followed.
 * </p>
 *
 * <p>
 * Identity: Java reads a struct as an exact str object, made once per struct and remembered in the
 * ring, so the same struct read again (the key and the value of one {@code PyDict_SetItem}, then the
 * element of an argument tuple) is the same object while its entry lives. A str that C stores into
 * a container is therefore an object the container keeps, and C reading it back gets that object's
 * stable stub. A bare Java string passed to C and handed back is an equal, new object
 * ({@code f(s) is s} is false), as for any str that crosses through a stub.
 * </p>
 *
 * Single-threaded: callers hold the GIL.
 */
public final class NativeStr {

    public static final long OFF_REFCNT = 0;
    public static final long OFF_TYPE = 8;
    public static final long OFF_LENGTH = 16;
    public static final long OFF_HASH = 24;
    public static final long OFF_STATE = 32;
    public static final long OFF_JSLOT = 36;
    public static final long OFF_UTF8_LENGTH = 40;
    public static final long OFF_UTF8 = 48;
    public static final long ASCII_HEADER = 40;
    public static final long COMPACT_HEADER = 56;

    public static final int MARK = 0x80000000;
    public static final int SLOT_MASK = 0x7fffffff;

    // state bits (clang/gcc bitfield order on little-endian targets: first field in the low bits)
    public static final int STATE_KIND_SHIFT = 2;
    public static final int STATE_COMPACT = 1 << 5;
    public static final int STATE_ASCII = 1 << 6;

    // region layout, relative to st + NativeComplex.S_STR (must match GraalPyStrState in unicodeobject.c)
    public static final long R_TYPE = 0;
    public static final long R_COUNTERS = 8;
    public static final int N_COUNTERS = 8;
    public static final long REGION_SIZE = R_COUNTERS + 8 * N_COUNTERS;

    public static final int C_JAVA_TO_C = 0;
    public static final int C_C_TO_JAVA = 1;
    public static final int C_RING_HIT = 2;
    public static final int C_JAVA_FREE = 3;
    public static final int C_C_ALLOC = 4;   // updated by C
    public static final int C_C_FREE = 5;    // updated by C
    public static final int C_JAVA_BYTES = 6;
    public static final String[] COUNTER_NAMES = {"str_java_to_c", "str_c_to_java", "str_ring_hit", "str_java_free", "str_c_alloc", "str_c_free", "str_java_bytes", ""};

    public static final int RING_SIZE = 4096;   // power of two
    private static final Object[] RING_OBJ = new Object[RING_SIZE];
    private static final long[] RING_PTR = new long[RING_SIZE];
    private static int ringCursor;

    private static final long[] JAVA_COUNTS = new long[N_COUNTERS];

    private NativeStr() {
    }

    public static final boolean COUNTERS = NativeComplex.COUNTERS;

    public static void count(int idx, long n) {
        if (COUNTERS) {
            JAVA_COUNTS[idx] += n;
        }
    }

    private static long region(long st) {
        return st + NativeComplex.S_STR;
    }

    /** Type pointer of {@code PyUnicode_Type}, or 0 if the scheme is off or C is not initialized. */
    public static long getStrType(long st) {
        return st == 0 ? 0 : UNSAFE.getLong(region(st) + R_TYPE);
    }

    /** True if {@code p} (a non-handle PyObject pointer) is a str struct of this scheme. */
    public static boolean isNativeStr(long st, long p) {
        long type = getStrType(st);
        return type != 0 && UNSAFE.getLong(p + OFF_TYPE) == type && (UNSAFE.getInt(p + OFF_JSLOT) & MARK) != 0;
    }

    public static boolean isNativeStr(long st, long p, long obType) {
        return obType == getStrType(st) && obType != 0 && (UNSAFE.getInt(p + OFF_JSLOT) & MARK) != 0;
    }

    @TruffleBoundary
    public static long[] snapshot(long st, boolean reset) {
        long[] res = new long[N_COUNTERS];
        for (int i = 0; i < N_COUNTERS; i++) {
            res[i] = JAVA_COUNTS[i] + (st != 0 ? UNSAFE.getLong(region(st) + R_COUNTERS + 8L * i) : 0);
            if (reset) {
                JAVA_COUNTS[i] = 0;
                if (st != 0) {
                    UNSAFE.putLong(region(st) + R_COUNTERS + 8L * i, 0);
                }
            }
        }
        return res;
    }

    // ---------------------------------------------------------------- struct

    public static int kind(long p) {
        return (UNSAFE.getInt(p + OFF_STATE) >>> STATE_KIND_SHIFT) & 7;
    }

    public static boolean isAscii(long p) {
        return (UNSAFE.getInt(p + OFF_STATE) & STATE_ASCII) != 0;
    }

    public static long length(long p) {
        return UNSAFE.getLong(p + OFF_LENGTH);
    }

    public static long data(long p) {
        return p + (isAscii(p) ? ASCII_HEADER : COMPACT_HEADER);
    }

    /**
     * Allocates a struct with count 1 and an initialized header; the caller writes {@code length *
     * kind} bytes of characters at {@link #data}. The NUL code unit is written here.
     */
    public static long allocate(long st, long length, int kind, boolean ascii) {
        long header = ascii ? ASCII_HEADER : COMPACT_HEADER;
        long size = header + (length + 1) * kind;
        long p = UNSAFE.allocateMemory(size);
        UNSAFE.putLong(p + OFF_REFCNT, 1);
        UNSAFE.putLong(p + OFF_TYPE, getStrType(st));
        UNSAFE.putLong(p + OFF_LENGTH, length);
        UNSAFE.putLong(p + OFF_HASH, -1);
        UNSAFE.putInt(p + OFF_STATE, (kind << STATE_KIND_SHIFT) | STATE_COMPACT | (ascii ? STATE_ASCII : 0));
        UNSAFE.putInt(p + OFF_JSLOT, MARK);
        if (!ascii) {
            UNSAFE.putLong(p + OFF_UTF8_LENGTH, 0);
            UNSAFE.putLong(p + OFF_UTF8, 0);
        }
        long end = p + header + length * kind;
        if (kind == 1) {
            UNSAFE.putByte(end, (byte) 0);
        } else if (kind == 2) {
            UNSAFE.putShort(end, (short) 0);
        } else {
            UNSAFE.putInt(end, 0);
        }
        count(C_JAVA_TO_C, 1);
        count(C_JAVA_BYTES, size);
        return p;
    }

    /** Drops one count; frees the struct (and its UTF-8 cache) when it reaches zero. */
    public static void decref(long p) {
        long rc = UNSAFE.getLong(p + OFF_REFCNT) - 1;
        UNSAFE.putLong(p + OFF_REFCNT, rc);
        if (rc == 0) {
            free(p);
        }
    }

    private static void free(long p) {
        if (!isAscii(p)) {
            long utf8 = UNSAFE.getLong(p + OFF_UTF8);
            if (utf8 != 0) {
                UNSAFE.freeMemory(utf8);
            }
        }
        UNSAFE.putLong(p + OFF_TYPE, 0);
        UNSAFE.freeMemory(p);
        count(C_JAVA_FREE, 1);
    }

    // ---------------------------------------------------------------- same string twice in one call

    private static TruffleString lastStr;
    private static long lastPtr;
    private static long lastIdx = -1;

    /**
     * The struct made for {@code s} as the most recent borrowed argument, if it is still held on
     * the borrowed stack (so alive until the outermost downcall returns); else 0. Gives one
     * pointer for one string passed twice in a call ({@code f(s, s)}) and saves the copy.
     */
    public static long lastBorrowed(long st, TruffleString s) {
        if (lastStr == s && lastIdx >= 0 && NativeComplex.borrowAt(st, lastIdx) == lastPtr) {
            return lastPtr;
        }
        return 0;
    }

    /**
     * Called when the borrowed stack is released down to {@code mark}: the remembered struct is no
     * longer held there and may be freed. Without this, a later struct allocated at the same address
     * and pushed at the same stack index would pass the check in {@link #lastBorrowed} and be
     * handed out for {@code s} although it holds a different string.
     */
    public static void forgetBorrowedAbove(long mark) {
        if (lastIdx >= mark) {
            lastStr = null;
            lastPtr = 0;
            lastIdx = -1;
        }
    }

    public static void setLastBorrowed(long st, TruffleString s, long p) {
        lastStr = s;
        lastPtr = p;
        lastIdx = NativeComplex.borrowMark(st) - 1;
    }

    public static void incref(long p) {
        UNSAFE.putLong(p + OFF_REFCNT, UNSAFE.getLong(p + OFF_REFCNT) + 1);
    }

    // ---------------------------------------------------------------- identity ring

    /** Whether struct {@code p} holds the same code points as {@code s} (for assertions). */
    @TruffleBoundary
    public static boolean contentEquals(long p, TruffleString s) {
        int n = s.codePointLengthUncached(TS_ENCODING);
        if (length(p) != n) {
            return false;
        }
        int k = kind(p);
        long d = data(p);
        for (int i = 0; i < n; i++) {
            int c = k == 1 ? UNSAFE.getByte(d + i) & 0xFF : k == 2 ? UNSAFE.getShort(d + 2L * i) & 0xFFFF : UNSAFE.getInt(d + 4L * i);
            if (c != s.codePointAtIndexUncached(i, TS_ENCODING)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remembers that struct {@code p} holds the value of {@code o}: the Java string it was copied
     * from, or the str object Java made when it read the struct.
     */
    public static void remember(long p, Object o) {
        int k = ringCursor;
        ringCursor = (k + 1) & (RING_SIZE - 1);
        RING_OBJ[k] = o;
        RING_PTR[k] = p;
        UNSAFE.putInt(p + OFF_JSLOT, MARK | (k + 1));
    }

    /** The object struct {@code p} was made from or converted to, if still known; else null. */
    public static Object recall(long p) {
        int k = (UNSAFE.getInt(p + OFF_JSLOT) & SLOT_MASK) - 1;
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.LIKELY_PROBABILITY, k >= 0 && k < RING_SIZE) && RING_PTR[k] == p) {
            count(C_RING_HIT, 1);
            return RING_OBJ[k];
        }
        return null;
    }
}
