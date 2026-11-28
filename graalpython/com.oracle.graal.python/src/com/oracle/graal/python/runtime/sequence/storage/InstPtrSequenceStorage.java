/*
 * Copyright (c) 2026, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.runtime.sequence.storage;

import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.runtime.nativeaccess.NativeInstance;

/**
 * Pointer-aware list storage for native-layout instances (see {@link NativeInstance}).
 * Every slot below {@code length} holds a native-instance word: a counted pointer to an instance
 * struct (tag 0, holds one reference) or the {@code None} word (holds nothing). Slots at or above
 * {@code length} are 0. Shares the count-exact array operations and the Cleaner of
 * {@link ComplexPtrSequenceStorage}; the element-kind flag makes their reference operations
 * instance-aware. A store of anything but an instance or {@code None} generalizes the list to an
 * {@link ObjectSequenceStorage} (instances are then pinned there).
 */
public final class InstPtrSequenceStorage extends ComplexPtrSequenceStorage {

    public InstPtrSequenceStorage(long state, int capacity) {
        super(state, capacity, true);
        NativeInstance.count(NativeInstance.C_LIST_NEW);
    }

    /** Takes ownership of {@code length} counted words in {@code elements}. */
    public InstPtrSequenceStorage(long state, long[] elements, int length) {
        super(state, elements, length, true);
        NativeInstance.count(NativeInstance.C_LIST_NEW);
    }

    @Override
    protected ComplexPtrSequenceStorage newSameKind(long[] elements, int len) {
        return new InstPtrSequenceStorage(state, elements, len);
    }

    @Override
    public InstPtrSequenceStorage createEmpty(int newCapacity) {
        return new InstPtrSequenceStorage(state, Math.max(newCapacity, 1));
    }

    public long getWord(int idx) {
        return values[idx];
    }

    /** Writes an already counted word into slot idx (below length); drops the old word's reference. */
    public void setWordOwned(int idx, long w) {
        NativeInstance.count(NativeInstance.C_LIST_STORE);
        long old = values[idx];
        values[idx] = w;
        if (old != 0) {
            decw(old);
        } else {
            ensureCleaner();
        }
    }

    /** Inserts an already counted word at idx. */
    public void insertWordOwned(int idx, long w) {
        ensureCapacity(length + 1);
        if (idx < length) {
            com.oracle.graal.python.util.PythonUtils.arraycopy(values, idx, values, idx + 1, length - idx);
        }
        values[idx] = 0;
        setWordOwned(idx, w);
        incLength();
    }

    /** Boxes all elements (in-flight instances or None; no count change). */
    @Override
    public Object[] getCopyOfInternalArray(com.oracle.graal.python.PythonLanguage language) {
        Object[] boxed = new Object[length];
        for (int i = 0; i < length; i++) {
            boxed[i] = box(values[i]);
        }
        return boxed;
    }

    static Object box(long w) {
        if (w == NativeInstance.W_NONE) {
            return PNone.NONE;
        }
        NativeInstance.count(NativeInstance.C_LIST_INFLIGHT);
        return NativeInstance.loadInstanceUncounted(w);
    }

    /**
     * Storage for a list literal (or constant list) whose elements are all native-layout instances
     * and/or None, with every word counted; null if any element is something else, if no opt-in
     * class exists, or at image build time.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static InstPtrSequenceStorage tryCreate(Object[] elements, int len, int capacity) {
        if (len == 0 || NativeInstance.NO_CLASSES.isValid() || org.graalvm.nativeimage.ImageInfo.inImageBuildtimeCode()) {
            return null;
        }
        for (int i = 0; i < len; i++) {
            if (!NativeInstance.isListWordValue(elements[i])) {
                return null;
            }
        }
        com.oracle.graal.python.runtime.PythonContext ctx = com.oracle.graal.python.runtime.PythonContext.get(null);
        long st = NativeInstance.state(ctx);
        long[] words = new long[Math.max(capacity, len)];
        for (int i = 0; i < len; i++) {
            words[i] = NativeInstance.toWordGeneric(ctx, st, elements[i]);
            NativeInstance.count(NativeInstance.C_LIST_STORE);
        }
        return new InstPtrSequenceStorage(st, words, len);
    }

    @Override
    public StorageType getElementType() {
        return StorageType.InstPtr;
    }
}
