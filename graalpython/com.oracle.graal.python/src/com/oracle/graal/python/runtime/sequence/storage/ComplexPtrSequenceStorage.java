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

import java.util.Arrays;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.objects.complex.PComplex;
import com.oracle.graal.python.runtime.AsyncHandler;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.nativeaccess.NativeComplex;
import com.oracle.graal.python.runtime.nativeaccess.NativeInstance;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * List storage of exact {@code complex} values as raw pointers to native,
 * reference-counted structs (see {@link NativeComplex}). Every non-zero slot below {@code length}
 * holds one counted reference; slots at or above {@code length} are always 0. When the storage
 * object dies, a {@link Cleaner} (phantom reference, declared shim) drops the remaining references.
 */
public class ComplexPtrSequenceStorage extends ArrayBasedSequenceStorage {

    protected long[] values;
    protected final long state;
    /**
     * Element kind. false = raw complex pointers; true = native-instance
     * words ({@link InstPtrSequenceStorage}: counted instance pointer or the {@code None} word).
     */
    protected final boolean inst;
    private Cleaner cleaner;

    public ComplexPtrSequenceStorage(long state, int capacity) {
        this(state, capacity, false);
    }

    protected ComplexPtrSequenceStorage(long state, int capacity, boolean inst) {
        this.state = state;
        this.inst = inst;
        this.values = new long[capacity];
        this.capacity = capacity;
        this.length = 0;
    }

    /** Takes ownership of {@code length} counted references in {@code elements}. */
    public ComplexPtrSequenceStorage(long state, long[] elements, int length) {
        this(state, elements, length, false);
    }

    protected ComplexPtrSequenceStorage(long state, long[] elements, int length, boolean inst) {
        this.state = state;
        this.inst = inst;
        this.values = elements;
        this.capacity = elements.length;
        this.length = length;
        if (length > 0) {
            registerCleaner();
        }
    }

    /** New storage of the same kind taking ownership of the counted words in {@code elements}. */
    protected ComplexPtrSequenceStorage newSameKind(long[] elements, int len) {
        return new ComplexPtrSequenceStorage(state, elements, len);
    }

    public final boolean isInst() {
        return inst;
    }

    // ------------------------------------------------------------------ reference operations by kind

    /** Adds the reference a word holds (instance words: only tag-0 pointers hold one). */
    static void incw(boolean inst, long w) {
        if (inst) {
            if ((w & 3) == 0 && w != 0) {
                NativeInstance.checkStruct(w);
                NativeInstance.incref(w);
            }
        } else if (w != 0) {
            NativeComplex.incref(w);
        }
    }

    /** Drops the reference a word holds. */
    static void decw(boolean inst, long st, long w) {
        if (inst) {
            if ((w & 3) == 0 && w != 0) {
                NativeInstance.checkStruct(w);
                NativeInstance.decref(st, w);
            }
        } else if (w != 0) {
            NativeComplex.decref(st, w);
        }
    }

    public final void incw(long w) {
        incw(inst, w);
    }

    public final void decw(long w) {
        decw(inst, state, w);
    }

    public long getState() {
        return state;
    }

    // ------------------------------------------------------------------ lifetime

    static final class Cleaner extends AsyncHandler.SharedFinalizer.FinalizableReference {
        long[] values;
        final long state;
        final boolean inst;

        Cleaner(ComplexPtrSequenceStorage storage, AsyncHandler.SharedFinalizer finalizer) {
            super(storage, finalizer);
            this.values = storage.values;
            this.state = storage.state;
            this.inst = storage.inst;
        }

        @Override
        public AsyncHandler.AsyncAction release() {
            final long[] v = values;
            values = null;
            if (v == null) {
                return null;
            }
            if (inst) {
                return (context, access) -> {
                    NativeInstance.count(NativeInstance.C_LIST_CLEANER);
                    releaseAll(true, state, v, v.length);
                };
            }
            return (context, access) -> releaseAll(false, state, v, v.length);
        }
    }

    @TruffleBoundary
    private void registerCleaner() {
        cleaner = new Cleaner(this, PythonContext.get(null).getSharedFinalizer());
    }

    protected final void ensureCleaner() {
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, cleaner == null)) {
            registerCleaner();
        }
    }

    private void setValues(long[] newValues) {
        values = newValues;
        capacity = newValues.length;
        if (cleaner != null) {
            cleaner.values = newValues;
        }
    }

    static void releaseAll(boolean inst, long st, long[] v, int n) {
        for (int i = 0; i < n; i++) {
            long p = v[i];
            if (p != 0) {
                v[i] = 0;
                decw(inst, st, p);
            }
        }
    }

    /**
     * Drops every element reference (count -1 each) and empties the storage. Used before the storage
     * is replaced by a generalized one.
     */
    public void releaseAllAndClear() {
        // whole array: a failed bulk operation may have left counted slots above length
        releaseAll(inst, state, values, values.length);
        length = 0;
    }

    /**
     * Replaces slots [lo, hi) by the {@code n} already counted pointers in {@code owned}; drops the
     * replaced references.
     */
    public void replaceRange(int lo, int hi, long[] owned, int n) {
        int len = length;
        int growth = n - (hi - lo);
        ensureCapacity(len + growth);
        for (int i = lo; i < hi; i++) {
            long p = values[i];
            values[i] = 0;
            if (p != 0) {
                decw(p);
            }
        }
        if (growth != 0) {
            PythonUtils.arraycopy(values, hi, values, lo + n, len - hi);
        }
        if (n > 0) {
            PythonUtils.arraycopy(owned, 0, values, lo, n);
        }
        int newLen = len + growth;
        for (int i = newLen; i < len; i++) {
            values[i] = 0;
        }
        length = newLen;
        if (newLen > 0) {
            ensureCleaner();
        }
    }

    /** Extended-slice assignment: slot start + i*step gets owned[i] (counted); old refs dropped. */
    public void setStepped(int start, int step, long[] owned, int n) {
        for (int i = 0, cur = start; i < n; i++, cur += step) {
            long old = values[cur];
            values[cur] = owned[i];
            if (old != 0) {
                decw(old);
            }
        }
    }

    /** Deletes the slice (start, step, sliceLength) as adjusted by SliceInfo, dropping references. */
    public void deleteSlice(int start, int step, int sliceLength) {
        if (sliceLength <= 0) {
            return;
        }
        if (step == 1) {
            replaceRange(start, start + sliceLength, null, 0);
            return;
        }
        int len = length;
        boolean[] removed = new boolean[len];
        for (int i = 0, cur = start; i < sliceLength; i++, cur += step) {
            removed[cur] = true;
        }
        int j = 0;
        for (int i = 0; i < len; i++) {
            long p = values[i];
            if (removed[i]) {
                if (p != 0) {
                    decw(p);
                }
            } else {
                values[j++] = p;
            }
        }
        for (int i = j; i < len; i++) {
            values[i] = 0;
        }
        length = j;
    }

    /** Copy of the pointers [0, length), each incref'd. */
    public long[] getCountedPtrs() {
        long[] res = new long[length];
        for (int i = 0; i < length; i++) {
            long p = values[i];
            incw(p);
            res[i] = p;
        }
        return res;
    }

    /** New storage with the elements repeated {@code times} times, each slot counted. */
    public ComplexPtrSequenceStorage repeat(int times) throws ArithmeticException {
        int len = length;
        int newLen = Math.multiplyExact(len, times);
        long[] arr = new long[Math.max(newLen, 1)];
        for (int t = 0; t < times; t++) {
            for (int i = 0; i < len; i++) {
                long p = values[i];
                incw(p);
                arr[t * len + i] = p;
            }
        }
        return newSameKind(arr, newLen);
    }

    // ------------------------------------------------------------------ capacity

    private void increaseCapacityExactWithCopy(int newCapacity) {
        setValues(PythonUtils.arrayCopyOf(values, newCapacity));
    }

    public void ensureCapacity(int newCapacity) throws ArithmeticException {
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.UNLIKELY_PROBABILITY, newCapacity > capacity)) {
            increaseCapacityExactWithCopy(capacityFor(newCapacity));
        }
    }

    @Override
    public ComplexPtrSequenceStorage createEmpty(int newCapacity) {
        return new ComplexPtrSequenceStorage(state, newCapacity);
    }

    @Override
    public void setNewLength(int newLength) {
        // slots at or above length must be 0; shrinking drops references
        if (newLength < length) {
            releaseRange(newLength, length);
        }
        this.length = newLength;
    }

    /** Drops the references in slots [from, to) and zeroes them. */
    public void releaseRange(int from, int to) {
        for (int i = from; i < to; i++) {
            long p = values[i];
            if (p != 0) {
                values[i] = 0;
                decw(p);
            }
        }
    }

    // ------------------------------------------------------------------ element access

    public long getPtr(int idx) {
        return values[idx];
    }

    public PComplex getItemNormalized(PythonLanguage language, int idx) {
        assert !inst;
        return NativeComplex.load(language, values[idx]);
    }

    /** Overwrites a live slot: materialise, +1 new, write, -1 old. */
    public void setItemNormalized(int idx, PComplex value) {
        assert !inst;
        long p = NativeComplex.materialize(state, value);
        NativeComplex.incref(p);
        long old = values[idx];
        values[idx] = p;
        if (old != 0) {
            NativeComplex.decref(state, old);
        } else {
            ensureCleaner();
        }
    }

    /** Writes a slot that holds no reference (append / insert after shifting). */
    public void initItemNormalized(int idx, PComplex value) {
        assert !inst;
        long p = NativeComplex.materialize(state, value);
        NativeComplex.incref(p);
        ensureCleaner();
        values[idx] = p;
    }

    /** Writes an already counted pointer into a slot that holds no reference. */
    public void initPtrOwned(int idx, long p) {
        ensureCleaner();
        values[idx] = p;
    }

    public void insertItem(int idx, PComplex value) {
        ensureCapacity(length + 1);
        if (idx < length) {
            PythonUtils.arraycopy(values, idx, values, idx + 1, length - idx);
        }
        values[idx] = 0;
        initItemNormalized(idx, value);
        incLength();
    }

    /** Removes the slot at idx and returns its counted pointer (ownership passes to the caller). */
    public long removePtr(int idx) {
        long p = values[idx];
        int n = length;
        if (idx < n - 1) {
            PythonUtils.arraycopy(values, idx + 1, values, idx, n - idx - 1);
        }
        values[n - 1] = 0;
        length = n - 1;
        return p;
    }

    public void reverse() {
        if (length > 0) {
            int head = 0;
            int tail = length - 1;
            int middle = (length - 1) / 2;
            for (; head <= middle; head++, tail--) {
                long temp = values[head];
                values[head] = values[tail];
                values[tail] = temp;
            }
        }
    }

    /** New storage with the elements [start, stop) by step, each incref'd. */
    public ComplexPtrSequenceStorage getSliceCopy(int start, int step, int sliceLength) {
        long[] newArray = new long[Math.max(sliceLength, 1)];
        for (int i = start, j = 0; j < sliceLength; i += step, j++) {
            long p = values[i];
            incw(p);
            newArray[j] = p;
        }
        return newSameKind(newArray, sliceLength);
    }

    /** Copy with every element incref'd. */
    public ComplexPtrSequenceStorage copyCounted() {
        return getSliceCopy(0, 1, length);
    }

    /** Boxes all elements as in-flight complex objects (no count change). */
    public Object[] getCopyOfInternalArray(PythonLanguage language) {
        Object[] boxed = new Object[length];
        for (int i = 0; i < length; i++) {
            boxed[i] = NativeComplex.load(language, values[i]);
        }
        return boxed;
    }

    public long[] getInternalPtrArray() {
        return values;
    }

    @Override
    public Object getIndicativeValue() {
        // ListGeneralizationNode.doEmptyStorage then creates an empty pointer storage
        return this;
    }

    /**
     * Generic array-based code must not copy the raw pointers without adjusting counts; the
     * storage nodes route every operation on this storage through explicit specializations.
     */
    @Override
    public Object getInternalArrayObject() {
        throw CompilerDirectives.shouldNotReachHere("ComplexPtrSequenceStorage: raw array access");
    }

    @Override
    public Object getCopyOfInternalArrayObject() {
        throw CompilerDirectives.shouldNotReachHere("ComplexPtrSequenceStorage: raw array copy");
    }

    @Override
    public void setInternalArrayObject(Object arrayObject) {
        throw CompilerDirectives.shouldNotReachHere("ComplexPtrSequenceStorage: raw array replace");
    }

    @Override
    public StorageType getElementType() {
        return StorageType.ComplexPtr;
    }

    @TruffleBoundary
    public long[] debugCopy() {
        return Arrays.copyOf(values, length);
    }
}
