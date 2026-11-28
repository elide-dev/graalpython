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

import static com.oracle.graal.python.runtime.nativeaccess.NativeInstance.HEADER;
import static com.oracle.graal.python.runtime.nativeaccess.NativeInstance.OFF_META;
import static com.oracle.graal.python.runtime.nativeaccess.NativeInstance.W_EMPTY;
import static com.oracle.graal.python.runtime.nativeaccess.NativeInstance.W_FALSE;
import static com.oracle.graal.python.runtime.nativeaccess.NativeInstance.W_NONE;
import static com.oracle.graal.python.runtime.nativeaccess.NativeInstance.W_TRUE;
import static com.oracle.graal.python.runtime.nativeaccess.NativeMemory.UNSAFE;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.builtins.objects.object.PNativeLayoutObject;
import com.oracle.graal.python.builtins.objects.type.PythonClass;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;

/**
 * Nodes for slot access on native-layout instances. Hand-written (no DSL); each node
 * profiles the word kinds it has seen in {@code @CompilationFinal} bits so compiled code contains only
 * those branches. The uncached instances (for uncached callers) always take the generic path.
 */
public final class NativeSlotNodes {
    private NativeSlotNodes() {
    }

    /** Inline depth of making nested not-yet-native objects native; deeper levels use the boundary worklist. */
    static final int MAX_INLINE_DEPTH = 2;

    // seen bits
    static final int S_INT = 1;
    static final int S_LONG = 1 << 1;
    static final int S_CONST = 1 << 2;
    static final int S_INST = 1 << 3;
    static final int S_COMPLEX = 1 << 4;
    static final int S_HANDLE = 1 << 5;
    static final int S_EMPTY = 1 << 6;
    static final int S_NEW_INST = 1 << 7;   // not-yet-native instance stored

    // ------------------------------------------------------------------ word -> value

    public static final class FromWordNode extends Node {
        private static final FromWordNode UNCACHED = new FromWordNode(true, NativeInstance.C_INFLIGHT);
        private static final FromWordNode UNCACHED_LIST = new FromWordNode(true, NativeInstance.C_LIST_INFLIGHT);
        private final boolean uncached;
        /** Counter for in-flight objects built by this node (slot loads or list loads). */
        private final int inflightCounter;
        @CompilationFinal private int seen;
        @CompilationFinal private int cachedId;          // 0 = uninitialized, -1 = generic
        @CompilationFinal private PythonClass cachedClass;

        private FromWordNode(boolean uncached, int inflightCounter) {
            this.uncached = uncached;
            this.inflightCounter = inflightCounter;
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        public static FromWordNode create() {
            return new FromWordNode(false, NativeInstance.C_INFLIGHT);
        }

        /** For loads from pointer-aware lists (counted as list loads). */
        @com.oracle.truffle.api.dsl.NeverDefault
        public static FromWordNode createForList() {
            return new FromWordNode(false, NativeInstance.C_LIST_INFLIGHT);
        }

        public static FromWordNode getUncachedForList() {
            return UNCACHED_LIST;
        }

        @Override
        public boolean isAdoptable() {
            return !uncached;
        }

        private void see(int bit) {
            if ((seen & bit) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seen |= bit;
            }
        }

        /** Returns the value, or {@code null} for an empty slot. */
        public Object execute(long w) {
            if (uncached) {
                if (inflightCounter == NativeInstance.C_LIST_INFLIGHT && (w & 3) == 0 && w != 0) {
                    NativeInstance.count(inflightCounter);
                    return NativeInstance.loadInstanceUncounted(w);
                }
                return NativeInstance.fromWordGeneric(PythonContext.get(null), w);
            }
            int tag = (int) w & 3;
            if (tag == 1) {
                long v = w >> 2;
                if (v == (int) v) {
                    see(S_INT);
                    return (int) v;
                }
                see(S_LONG);
                return v;
            } else if (tag == 0) {
                if (w == W_EMPTY) {
                    see(S_EMPTY);
                    return null;
                }
                see(S_INST);
                return loadInstance(w);
            } else if (tag == 3) {
                if ((w & 4) == 0) {
                    see(S_CONST);
                    if (w == W_NONE) {
                        return PNone.NONE;
                    }
                    return w == W_TRUE;
                }
                see(S_HANDLE);
                return NativeInstance.handleLoad(w);
            } else {
                see(S_COMPLEX);
                return NativeComplex.load(PythonLanguage.get(this), w & ~3L);
            }
        }

        private PNativeLayoutObject loadInstance(long p) {
            NativeInstance.checkStruct(p);
            int cid = (int) UNSAFE.getLong(p + OFF_META);
            PythonClass cls;
            if (cid == cachedId) {
                cls = cachedClass;
            } else {
                if (cachedId == 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedId = cid;
                    cachedClass = NativeInstance.classOf(cid);
                } else if (cachedId != -1) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedId = -1;
                    cachedClass = null;
                }
                cls = NativeInstance.classOf(cid);
            }
            NativeInstance.count(inflightCounter);
            return new PNativeLayoutObject(cls, cls.getInstanceShape(), p, NativeInstance.serialOf(p));
        }
    }

    // ------------------------------------------------------------------ value -> word

    public static final class ToWordNode extends Node {
        private static final ToWordNode UNCACHED = new ToWordNode(true, MAX_INLINE_DEPTH);
        private final boolean uncached;
        private final int depth;
        @CompilationFinal private int seen;
        @Child private MakeNativeNode makeNative;

        private ToWordNode(boolean uncached, int depth) {
            this.uncached = uncached;
            this.depth = depth;
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        public static ToWordNode create() {
            return new ToWordNode(false, 0);
        }

        public static ToWordNode getUncached() {
            return UNCACHED;
        }

        static ToWordNode create(int depth) {
            return new ToWordNode(false, depth);
        }

        @Override
        public boolean isAdoptable() {
            return !uncached;
        }

        private void see(int bit) {
            if ((seen & bit) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seen |= bit;
            }
        }

        /** Converts a value into a counted word (adds the reference the word holds). */
        public long execute(Object v) {
            if (uncached) {
                PythonContext ctx = PythonContext.get(null);
                return NativeInstance.toWordGeneric(ctx, NativeInstance.state(ctx), v);
            }
            if (v instanceof Integer i) {
                see(S_INT);
                return NativeInstance.tagInt(i);
            } else if (v instanceof PNativeLayoutObject o) {
                long p = o.getSlotsPtr();
                if (p == 0) {
                    see(S_NEW_INST);
                    p = getMakeNative().execute(o);
                } else {
                    see(S_INST);
                    NativeInstance.checkLive(o);
                }
                NativeInstance.incref(p);
                return p;
            } else if (v == PNone.NONE) {
                see(S_CONST);
                return W_NONE;
            } else if (v instanceof Boolean b) {
                see(S_CONST);
                return b ? W_TRUE : W_FALSE;
            } else if (v instanceof Long l && NativeInstance.fitsTagged(l)) {
                see(S_LONG);
                return NativeInstance.tagInt(l);
            } else if (v instanceof PComplex c && c.isExact()) {
                see(S_COMPLEX);
                return NativeInstance.complexWord(PythonContext.get(this), c);
            }
            see(S_HANDLE);
            return NativeInstance.handleStore(v);
        }

        private MakeNativeNode getMakeNative() {
            if (makeNative == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                makeNative = insert(new MakeNativeNode(depth));
            }
            return makeNative;
        }
    }

    // ------------------------------------------------------------------ making an object native

    /**
     * Allocates the struct for a not-yet-native object and converts its Java slots (exploded loop for
     * one cached class, so a virtual object stays virtual). Nested not-yet-native objects are made
     * native by nested nodes up to {@link #MAX_INLINE_DEPTH}, below that by the boundary worklist.
     */
    public static final class MakeNativeNode extends Node {
        private final int depth;
        @CompilationFinal private Object cachedClass;
        @CompilationFinal private int cachedId;
        @CompilationFinal private boolean generic;
        @Children private ToWordNode[] words;

        MakeNativeNode(int depth) {
            this.depth = depth;
        }

        public long execute(PNativeLayoutObject o) {
            Object cls = o.getPythonClass();
            if (cachedClass != null && cls == cachedClass) {
                return inline(o);
            }
            if (!generic && cachedClass == null && depth < MAX_INLINE_DEPTH) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                PythonClass pc = (PythonClass) cls;
                cachedId = pc.getNativeLayoutId();
                int n = NativeInstance.slotCountOf(cachedId);
                ToWordNode[] w = new ToWordNode[n];
                for (int i = 0; i < n; i++) {
                    w[i] = ToWordNode.create(depth + 1);
                }
                words = insert(w);
                cachedClass = cls;
                return inline(o);
            }
            if (!generic) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                generic = true;
            }
            PythonContext ctx = PythonContext.get(this);
            return NativeInstance.makeNativeSlow(ctx, NativeInstance.state(ctx), o);
        }

        @ExplodeLoop
        private long inline(PNativeLayoutObject o) {
            long st = NativeInstance.state(PythonContext.get(this));
            ToWordNode[] w = words;
            int n = w.length;
            long p = NativeInstance.alloc(st, cachedId, n);
            o.setSlotsPtr(p, NativeInstance.serialOf(p));
            Object[] js = o.getIndexedSlots();
            for (int i = 0; i < n; i++) {
                Object v = js == null ? null : js[i];
                long word = v == null ? W_EMPTY : w[i].execute(v);
                UNSAFE.putLong(p + HEADER + 8L * i, word);
            }
            o.setIndexedSlots(null);
            NativeInstance.count(NativeInstance.C_MAT_INLINE);
            return p;
        }
    }

    // ------------------------------------------------------------------ slot read / write

    public static final class ReadSlotNode extends Node {
        private static final ReadSlotNode UNCACHED = new ReadSlotNode(true);
        @Child private FromWordNode fromWord;

        private ReadSlotNode(boolean uncached) {
            this.fromWord = uncached ? FromWordNode.UNCACHED : FromWordNode.create();
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        public static ReadSlotNode create() {
            return new ReadSlotNode(false);
        }

        public static ReadSlotNode getUncached() {
            return UNCACHED;
        }

        @Override
        public boolean isAdoptable() {
            return this != UNCACHED;
        }

        @CompilationFinal private byte seenSides;   // 1 = native receiver seen, 2 = Java-slots receiver seen

        /**
         * Two-sided profile of "receiver is a native instance" for the caller's branch: a side never
         * seen becomes a deoptimization, so the two branches' results do not meet in a phi.
         */
        public boolean profileNative(boolean isNative) {
            if (this == UNCACHED) {
                return isNative;
            }
            if (isNative) {
                if ((seenSides & 1) == 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenSides |= 1;
                }
                return true;
            } else {
                if ((seenSides & 2) == 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenSides |= 2;
                }
                return false;
            }
        }

        /** Reads slot {@code index} of a native instance; {@code null} if it is not set. */
        public Object execute(PNativeLayoutObject o, int index) {
            NativeInstance.checkLive(o);
            long w = UNSAFE.getLong(o.getSlotsPtr() + HEADER + 8L * index);
            return fromWord.execute(w);
        }
    }

    public static final class WriteSlotNode extends Node {
        private static final WriteSlotNode UNCACHED = new WriteSlotNode(true);
        private final boolean uncached;
        @Child private ToWordNode toWord;
        @CompilationFinal private int seenOld;

        private WriteSlotNode(boolean uncached) {
            this.uncached = uncached;
            this.toWord = uncached ? ToWordNode.UNCACHED : ToWordNode.create();
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        public static WriteSlotNode create() {
            return new WriteSlotNode(false);
        }

        public static WriteSlotNode getUncached() {
            return UNCACHED;
        }

        @Override
        public boolean isAdoptable() {
            return this != UNCACHED;
        }

        /** Writes slot {@code index}; {@code value == null} deletes it. Returns false if deleting an unset slot. */
        public boolean execute(PNativeLayoutObject o, int index, Object value) {
            NativeInstance.checkLive(o);
            long w = value == null ? W_EMPTY : toWord.execute(value);
            long a = o.getSlotsPtr() + HEADER + 8L * index;
            long old = UNSAFE.getLong(a);
            UNSAFE.putLong(a, w);
            release(old);
            return old != W_EMPTY;
        }

        private void release(long old) {
            if (uncached) {
                NativeInstance.releaseSlow(old);
                return;
            }
            int tag = (int) old & 3;
            if (tag == 1 || old == W_EMPTY) {
                return;
            }
            if (tag == 0) {
                if ((seenOld & S_INST) == 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenOld |= S_INST;
                }
                NativeInstance.checkStruct(old);
                NativeInstance.decref(NativeInstance.state(PythonContext.get(this)), old);
            } else if (tag == 3 && (old & 4) == 0) {
                return; // constant
            } else {
                if ((seenOld & S_HANDLE) == 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenOld |= S_HANDLE;
                }
                NativeInstance.releaseSlow(old);
            }
        }
    }
}
