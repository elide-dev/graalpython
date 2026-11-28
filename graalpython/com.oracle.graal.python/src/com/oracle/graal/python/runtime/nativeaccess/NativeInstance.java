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
import static com.oracle.graal.python.util.PythonUtils.tsLiteral;

import java.util.ArrayList;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.builtins.objects.object.PNativeLayoutObject;
import com.oracle.graal.python.builtins.objects.type.PythonBuiltinClass;
import com.oracle.graal.python.builtins.objects.type.PythonClass;
import com.oracle.graal.python.runtime.AsyncHandler;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * Instances of opt-in {@code __slots__} classes as native, reference-counted structs.
 *
 * <p>
 * Struct: refcount @0 (long), meta @8 (class id in the low 32 bits, "recorded" flag in bit 32, debug
 * serial in bits 33..63), then one 8-byte word per slot @16. A free block keeps its free-list link
 * at @0 and has meta 0 (class id 0 is never used), so the debug check sees it as freed.
 * </p>
 *
 * <p>
 * Words (low two bits are the tag): {@code 00} counted pointer to a native instance (0 = slot not
 * set), {@code 01} tagged int ({@code value << 2}, 62 bits), {@code 10} counted pointer to a
 * native complex ({@code ptr | 2}), {@code 11} special: bit 2 clear = constant ({@code None} 3,
 * {@code False} 11, {@code True} 19), bit 2 set = handle ({@code index << 3 | 7}) into a Java table
 * that owns one strong reference per handle word.
 * </p>
 *
 * <p>
 * Counts: only heap references (slot words and pins). A struct whose count reaches zero is recorded
 * (once, flag in meta) in a native array; {@link #quiesce} frees recorded structs whose count is still
 * zero, recursively. Nothing is freed anywhere else. Single-threaded: callers hold the GIL.
 * </p>
 */
public final class NativeInstance {

    public static final boolean COUNTERS = Boolean.getBoolean("graalpy.slotsCounters");
    public static final boolean DEBUG = Boolean.getBoolean("graalpy.nativeSlotsDebug");

    public static final int MAX_SLOTS = 64;

    public static final long OFF_REFCNT = 0;
    public static final long OFF_META = 8;
    public static final long HEADER = 16;
    private static final long OFF_LINK = 0;
    static final long META_RECORDED = 1L << 32;
    static final int SERIAL_SHIFT = 33;

    public static final long W_EMPTY = 0;
    public static final long W_NONE = 3;
    public static final long W_FALSE = 11;
    public static final long W_TRUE = 19;
    static final long TAGGED_MIN = -(1L << 61);
    static final long TAGGED_MAX = (1L << 61) - 1;

    // state layout
    static final long S_BUMP = 0;
    static final long S_END = 8;
    static final long S_SERIAL = 16;
    static final long S_REC = 24;
    static final long S_RECLEN = 32;
    static final long S_RECCAP = 40;
    static final long S_FREE = 48;   // MAX_SLOTS + 1 free-list heads, by slot count
    static final long STATE_SIZE = S_FREE + 8L * (MAX_SLOTS + 1);
    private static final long ARENA_SIZE = 1024 * 1024;

    // counters
    public static final int C_ALLOC = 0;
    public static final int C_FREE = 1;
    public static final int C_INCREF = 2;
    public static final int C_DECREF = 3;
    public static final int C_PIN = 4;
    public static final int C_UNPIN = 5;
    public static final int C_HANDLE_STORE = 6;
    public static final int C_HANDLE_LOAD = 7;
    public static final int C_INFLIGHT = 8;       // in-flight Java objects created from slot loads
    public static final int C_RECORD = 9;         // structs entering the zero-count record
    public static final int C_MAT_INLINE = 10;    // made native by the inlined (one class) path
    public static final int C_MAT_SLOW = 11;      // made native by the boundary (worklist) path
    public static final int C_COMPLEX_STORE = 12;
    public static final int C_QUIESCE = 13;
    public static final int C_LIST_NEW = 14;        // pointer-aware list storages created
    public static final int C_LIST_STORE = 15;      // words stored into pointer-aware list storages
    public static final int C_LIST_INFLIGHT = 16;   // in-flight Java objects created from list loads
    public static final int C_LIST_GENERALIZE = 17; // pointer-aware list storages generalized to object storage
    public static final int C_LIST_CLEANER = 18;    // pointer-aware list storages released by the cleaner
    public static final int C_PIN_DICT = 19;        // pins at dict key/value stores (also counted in pin)
    public static final int C_PIN_CELL = 20;        // pins at cell stores (also counted in pin)
    public static final int C_PIN_ID = 21;          // pins for id()/default hash (also counted in pin)
    public static final int N_COUNTERS = 22;
    public static final String[] COUNTER_NAMES = {"inst_alloc", "inst_free", "incref", "decref", "pin", "unpin", "handle_store", "handle_load", "inflight_from_load",
                    "record", "make_native_inline", "make_native_slow", "complex_store", "quiesce", "list_new", "list_store", "inflight_from_list",
                    "list_generalize", "list_cleaner", "pin_dict", "pin_cell", "pin_id"};
    private static final long[] COUNTS = new long[N_COUNTERS];

    /** Valid while no opt-in class exists: all pin checks fold away. */
    public static final Assumption NO_CLASSES = Truffle.getRuntime().createAssumption("no native-layout classes");

    private static PythonClass[] classes = new PythonClass[16];
    private static int[] slotCounts = new int[16];
    private static int nClasses = 0;   // ids are 1..nClasses

    private static final TruffleString T___NATIVE_LAYOUT__ = tsLiteral("__native_layout__");

    private NativeInstance() {
    }

    public static void count(int idx) {
        if (COUNTERS) {
            COUNTS[idx]++;
        }
    }

    @TruffleBoundary
    public static long[] snapshot(boolean reset) {
        long[] res = COUNTS.clone();
        if (reset) {
            java.util.Arrays.fill(COUNTS, 0);
        }
        return res;
    }

    // ---------------------------------------------------------------- classes

    /**
     * Called by type creation after the slot descriptors exist. Opts the class in if it declares
     * {@code __native_layout__ = True} in its own namespace, has {@code object} as its only base,
     * non-empty {@code __slots__} and neither {@code __dict__} nor {@code __weakref__}.
     */
    @TruffleBoundary
    public static void maybeRegister(PythonClass cls, Object base, int nBases, int slotCount, boolean hasOwnSlots, boolean addDict, boolean addWeak) {
        if (!hasOwnSlots || addDict || addWeak || nBases != 1 || slotCount <= 0 || slotCount > MAX_SLOTS) {
            return;
        }
        if (!(base instanceof PythonBuiltinClass pbc && pbc.getType() == PythonBuiltinClassType.PythonObject)) {
            return;
        }
        Object marker = cls.getAttribute(T___NATIVE_LAYOUT__);
        if (marker != Boolean.TRUE) {
            return;
        }
        int id = ++nClasses;
        if (id >= classes.length) {
            classes = java.util.Arrays.copyOf(classes, classes.length * 2);
            slotCounts = java.util.Arrays.copyOf(slotCounts, slotCounts.length * 2);
        }
        classes[id] = cls;
        slotCounts[id] = slotCount;
        cls.setNativeLayoutId(id);
        NO_CLASSES.invalidate();
    }

    public static PythonClass classOf(int id) {
        return classes[id];
    }

    public static int slotCountOf(int id) {
        return slotCounts[id];
    }

    // ---------------------------------------------------------------- state and allocation

    @TruffleBoundary
    public static long createState() {
        long st = UNSAFE.allocateMemory(STATE_SIZE);
        UNSAFE.setMemory(st, STATE_SIZE, (byte) 0);
        return st;
    }

    public static long state(PythonContext ctx) {
        return ctx.getNativeInstanceState();
    }

    public static long alloc(long st, int classId, int n) {
        long head = st + S_FREE + 8L * n;
        long p = UNSAFE.getLong(head);
        if (p != 0) {
            UNSAFE.putLong(head, UNSAFE.getLong(p + OFF_LINK));
        } else {
            p = UNSAFE.getLong(st + S_BUMP);
            long size = HEADER + 8L * n;
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, p + size > UNSAFE.getLong(st + S_END))) {
                p = newArena(st, size);
            } else {
                UNSAFE.putLong(st + S_BUMP, p + size);
            }
        }
        UNSAFE.putLong(p + OFF_REFCNT, 0);
        long meta = classId;
        if (DEBUG) {
            long serial = UNSAFE.getLong(st + S_SERIAL) + 1;
            if (serial >= (1L << (64 - SERIAL_SHIFT))) {
                serial = 1;
            }
            UNSAFE.putLong(st + S_SERIAL, serial);
            meta |= serial << SERIAL_SHIFT;
        }
        UNSAFE.putLong(p + OFF_META, meta);
        count(C_ALLOC);
        return p;
    }

    public static long serialOf(long p) {
        return DEBUG ? UNSAFE.getLong(p + OFF_META) >>> SERIAL_SHIFT : 0;
    }

    @TruffleBoundary
    private static long newArena(long st, long size) {
        long arena = UNSAFE.allocateMemory(ARENA_SIZE);
        long p = arena;
        UNSAFE.putLong(st + S_BUMP, p + size);
        UNSAFE.putLong(st + S_END, arena + ARENA_SIZE);
        return p;
    }

    public static void incref(long p) {
        UNSAFE.putLong(p + OFF_REFCNT, UNSAFE.getLong(p + OFF_REFCNT) + 1);
        count(C_INCREF);
    }

    public static void decref(long st, long p) {
        long rc = UNSAFE.getLong(p + OFF_REFCNT) - 1;
        UNSAFE.putLong(p + OFF_REFCNT, rc);
        count(C_DECREF);
        if (rc == 0) {
            record(st, p);
        }
    }

    /** Puts a zero-count struct into the record (once; the flag is cleared when quiesce looks at it). */
    public static void record(long st, long p) {
        long meta = UNSAFE.getLong(p + OFF_META);
        if ((meta & META_RECORDED) == 0) {
            UNSAFE.putLong(p + OFF_META, meta | META_RECORDED);
            long len = UNSAFE.getLong(st + S_RECLEN);
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, len >= UNSAFE.getLong(st + S_RECCAP))) {
                growRecord(st);
            }
            UNSAFE.putLong(UNSAFE.getLong(st + S_REC) + 8 * len, p);
            UNSAFE.putLong(st + S_RECLEN, len + 1);
            count(C_RECORD);
        }
    }

    @TruffleBoundary
    private static void growRecord(long st) {
        long cap = UNSAFE.getLong(st + S_RECCAP);
        long newCap = cap == 0 ? 4096 : cap * 2;
        long old = UNSAFE.getLong(st + S_REC);
        long arr = UNSAFE.allocateMemory(8 * newCap);
        if (old != 0) {
            UNSAFE.copyMemory(old, arr, 8 * cap);
            UNSAFE.freeMemory(old);
        }
        UNSAFE.putLong(st + S_REC, arr);
        UNSAFE.putLong(st + S_RECCAP, newCap);
    }

    /**
     * Frees every recorded struct whose count is still zero; freeing drops the references held by
     * its slots, which may record more structs (processed in the same loop, no recursion).
     */
    @TruffleBoundary
    public static long quiesce(PythonContext ctx) {
        long st = ctx.peekNativeInstanceState();
        if (st == 0) {
            return 0;
        }
        count(C_QUIESCE);
        long freed = 0;
        long len;
        while ((len = UNSAFE.getLong(st + S_RECLEN)) > 0) {
            len--;
            UNSAFE.putLong(st + S_RECLEN, len);
            long p = UNSAFE.getLong(UNSAFE.getLong(st + S_REC) + 8 * len);
            long meta = UNSAFE.getLong(p + OFF_META);
            UNSAFE.putLong(p + OFF_META, meta & ~META_RECORDED);
            if (UNSAFE.getLong(p + OFF_REFCNT) == 0) {
                freeStruct(ctx, st, p);
                freed++;
            }
        }
        return freed;
    }

    private static void freeStruct(PythonContext ctx, long st, long p) {
        int cid = (int) UNSAFE.getLong(p + OFF_META);
        if (cid <= 0 || cid > nClasses) {
            throw CompilerDirectives.shouldNotReachHere("native instance: freeing a struct with bad class id " + cid + " at 0x" + Long.toHexString(p));
        }
        int n = slotCounts[cid];
        for (int i = 0; i < n; i++) {
            long a = p + HEADER + 8L * i;
            long w = UNSAFE.getLong(a);
            UNSAFE.putLong(a, DEBUG ? 0xDEADDEADDEADDEADL : 0);
            releaseGeneric(ctx, st, w);
        }
        UNSAFE.putLong(p + OFF_META, 0);
        long head = st + S_FREE + 8L * n;
        UNSAFE.putLong(p + OFF_LINK, UNSAFE.getLong(head));
        UNSAFE.putLong(head, p);
        count(C_FREE);
    }

    // ---------------------------------------------------------------- debug checks

    /** Debug mode: the Java object must refer to a live struct of the incarnation it was made for. */
    public static void checkLive(PNativeLayoutObject o) {
        if (DEBUG) {
            long p = o.getSlotsPtr();
            long meta = UNSAFE.getLong(p + OFF_META);
            if ((int) meta == 0 || (meta >>> SERIAL_SHIFT) != o.getSerial()) {
                useAfterFree(p, meta, o.getSerial());
            }
        }
    }

    /** Debug mode: a pointer found in a slot must point to a live struct. */
    public static void checkStruct(long p) {
        if (DEBUG) {
            long meta = UNSAFE.getLong(p + OFF_META);
            if ((int) meta == 0) {
                useAfterFree(p, meta, -1);
            }
        }
    }

    @TruffleBoundary
    private static void useAfterFree(long p, long meta, long expectedSerial) {
        String msg = "native instance: use after free at 0x" + Long.toHexString(p) + " (meta 0x" + Long.toHexString(meta) + ", expected serial " + expectedSerial + ")";
        System.err.println(msg);
        System.err.flush();
        throw new IllegalStateException(msg);
    }

    // ---------------------------------------------------------------- words

    public static boolean fitsTagged(long v) {
        return v >= TAGGED_MIN && v <= TAGGED_MAX;
    }

    public static long tagInt(long v) {
        return (v << 2) | 1;
    }

    public static boolean isHandleWord(long w) {
        return (w & 7) == 7;
    }

    /** Generic value -> word conversion (counted). Used by uncached paths and the slow path. */
    @TruffleBoundary
    public static long toWordGeneric(PythonContext ctx, long st, Object v) {
        if (v == null) {
            return W_EMPTY;
        } else if (v instanceof Integer i) {
            return tagInt(i);
        } else if (v instanceof Long l && fitsTagged(l)) {
            return tagInt(l);
        } else if (v instanceof Boolean b) {
            return b ? W_TRUE : W_FALSE;
        } else if (v == PNone.NONE) {
            return W_NONE;
        } else if (v instanceof PNativeLayoutObject o) {
            long p = o.getSlotsPtr();
            if (p == 0) {
                p = makeNativeSlow(ctx, st, o);
            } else {
                checkLive(o);
            }
            incref(p);
            return p;
        } else if (v instanceof PComplex c && c.isExact()) {
            return complexWord(ctx, c);
        }
        return handleStore(v);
    }

    public static long complexWord(PythonContext ctx, PComplex c) {
        long cst = ctx.getNativeComplexState();
        long p = NativeComplex.materialize(cst, c);
        NativeComplex.incref(p);
        count(C_COMPLEX_STORE);
        return p | 2;
    }

    /** Generic word -> value; {@code null} for an empty slot. */
    @TruffleBoundary
    public static Object fromWordGeneric(PythonContext ctx, long w) {
        int tag = (int) w & 3;
        if (tag == 1) {
            long v = w >> 2;
            return v == (int) v ? (Object) (int) v : (Object) v;
        } else if (tag == 0) {
            if (w == 0) {
                return null;
            }
            return loadInstanceGeneric(w);
        } else if (tag == 2) {
            return NativeComplex.load(ctx.getLanguage(), w & ~3L);
        } else if ((w & 4) == 0) {
            return constant(w);
        } else {
            return handleLoad(w);
        }
    }

    public static Object constant(long w) {
        if (w == W_NONE) {
            return PNone.NONE;
        } else if (w == W_TRUE) {
            return Boolean.TRUE;
        } else {
            return Boolean.FALSE;
        }
    }

    public static PNativeLayoutObject loadInstanceGeneric(long p) {
        count(C_INFLIGHT);
        return loadInstanceUncounted(p);
    }

    /** New in-flight object around {@code p} (no count change, no counter). */
    public static PNativeLayoutObject loadInstanceUncounted(long p) {
        checkStruct(p);
        int cid = (int) UNSAFE.getLong(p + OFF_META);
        PythonClass cls = classes[cid];
        return new PNativeLayoutObject(cls, cls.getInstanceShape(), p, serialOf(p));
    }

    /** Drops the reference a word holds (generic). */
    public static void releaseGeneric(PythonContext ctx, long st, long w) {
        int tag = (int) w & 3;
        if (tag == 0) {
            if (w != 0) {
                checkStruct(w);
                decref(st, w);
            }
        } else if (tag == 2) {
            NativeComplex.decref(ctx.getNativeComplexState(), w & ~3L);
        } else if (tag == 3 && (w & 4) != 0) {
            handleFree(w);
        }
    }

    @TruffleBoundary
    public static void releaseSlow(long w) {
        releaseGeneric(PythonContext.get(null), state(PythonContext.get(null)), w);
    }

    // ---------------------------------------------------------------- handle table (declared shim)

    private static Object[] handles = new Object[1024];
    private static int[] handleNext = new int[1024];
    private static int handleFree = -1;
    private static int handleTop = 0;

    @TruffleBoundary
    public static long handleStore(Object v) {
        int idx;
        if (handleFree >= 0) {
            idx = handleFree;
            handleFree = handleNext[idx];
        } else {
            if (handleTop == handles.length) {
                handles = java.util.Arrays.copyOf(handles, handles.length * 2);
                handleNext = java.util.Arrays.copyOf(handleNext, handleNext.length * 2);
            }
            idx = handleTop++;
        }
        handles[idx] = v;
        count(C_HANDLE_STORE);
        return ((long) idx << 3) | 7;
    }

    public static Object handleLoad(long w) {
        count(C_HANDLE_LOAD);
        return handles[(int) (w >>> 3)];
    }

    @TruffleBoundary
    public static void handleFree(long w) {
        int idx = (int) (w >>> 3);
        handles[idx] = null;
        handleNext[idx] = handleFree;
        handleFree = idx;
    }

    // ---------------------------------------------------------------- making native

    /**
     * Makes {@code o} and every not-yet-native opt-in object reachable from its Java slots native.
     * Two phases with an explicit worklist (no recursion): allocate structs and set the pointers,
     * then convert the Java slots into words. New structs start with count 0; the caller's store
     * adds the first count.
     */
    @TruffleBoundary
    public static long makeNativeSlow(PythonContext ctx, long st, PNativeLayoutObject o) {
        if (o.getSlotsPtr() != 0) {
            return o.getSlotsPtr();
        }
        ArrayList<PNativeLayoutObject> made = new ArrayList<>();
        allocFor(st, o);
        made.add(o);
        for (int k = 0; k < made.size(); k++) {
            Object[] js = made.get(k).getIndexedSlots();
            if (js != null) {
                for (Object v : js) {
                    if (v instanceof PNativeLayoutObject y && y.getSlotsPtr() == 0) {
                        allocFor(st, y);
                        made.add(y);
                    }
                }
            }
        }
        for (PNativeLayoutObject x : made) {
            int cid = ((PythonClass) x.getPythonClass()).getNativeLayoutId();
            int n = slotCounts[cid];
            long p = x.getSlotsPtr();
            Object[] js = x.getIndexedSlots();
            for (int i = 0; i < n; i++) {
                Object v = js == null || i >= js.length ? null : js[i];
                UNSAFE.putLong(p + HEADER + 8L * i, toWordGeneric(ctx, st, v));
            }
            x.setIndexedSlots(null);
            count(C_MAT_SLOW);
        }
        return o.getSlotsPtr();
    }

    private static void allocFor(long st, PNativeLayoutObject o) {
        int cid = ((PythonClass) o.getPythonClass()).getNativeLayoutId();
        long p = alloc(st, cid, slotCounts[cid]);
        o.setSlotsPtr(p, serialOf(p));
    }

    // ---------------------------------------------------------------- pins (Java-heap stores)

    /** Store-site hook: a native-layout object stored into a Java-heap location is made native and pinned. */
    public static void pinIfNative(Object value) {
        if (!NO_CLASSES.isValid() && value instanceof PNativeLayoutObject o && !o.isPinned()) {
            pin(o);
        }
    }

    /** Same as {@link #pinIfNative(Object)}, also counting the pin under {@code siteCounter}. */
    public static void pinIfNative(Object value, int siteCounter) {
        if (!NO_CLASSES.isValid() && value instanceof PNativeLayoutObject o && !o.isPinned()) {
            pin(o);
            count(siteCounter);
        }
    }

    /**
     * Identity for {@code id()} and the default hash: the struct pointer, so every in-flight copy of
     * a struct yields the same value. An object that is not native yet is made native and pinned (a
     * struct made native without a counted reference would never be recorded, i.e. leak; the pin
     * keeps it, and the identity, alive as long as this Java object). An object that is already
     * native is not pinned again.
     */
    @TruffleBoundary
    public static long identity(PNativeLayoutObject o) {
        long p = o.getSlotsPtr();
        if (p == 0) {
            pin(o);
            count(C_PIN_ID);
            p = o.getSlotsPtr();
        } else {
            checkLive(o);
        }
        return p;
    }

    /** Values a pointer-aware list holds: native-layout instances and None (only once an opt-in class exists). */
    public static boolean isListWordValue(Object v) {
        return v instanceof PNativeLayoutObject || (v == PNone.NONE && !NO_CLASSES.isValid());
    }

    public static void pinArray(Object[] values, int from, int to) {
        if (!NO_CLASSES.isValid()) {
            for (int i = from; i < to; i++) {
                pinIfNative(values[i]);
            }
        }
    }

    @TruffleBoundary
    public static void pin(PNativeLayoutObject o) {
        if (o.isPinned()) {
            return;
        }
        PythonContext ctx = PythonContext.get(null);
        long st = state(ctx);
        long p = o.getSlotsPtr();
        if (p == 0) {
            p = makeNativeSlow(ctx, st, o);
        } else {
            checkLive(o);
        }
        incref(p);
        o.setPinned();
        count(C_PIN);
        new PinRef(o, p, ctx.getSharedFinalizer());
    }

    static final class PinRef extends AsyncHandler.SharedFinalizer.FinalizableReference {
        private long ptr;

        PinRef(PNativeLayoutObject referent, long ptr, AsyncHandler.SharedFinalizer finalizer) {
            super(referent, finalizer);
            this.ptr = ptr;
        }

        @Override
        public AsyncHandler.AsyncAction release() {
            final long p = ptr;
            ptr = 0;
            if (p == 0) {
                return null;
            }
            return (context, access) -> {
                count(C_UNPIN);
                decref(state(context), p);
            };
        }
    }

}
