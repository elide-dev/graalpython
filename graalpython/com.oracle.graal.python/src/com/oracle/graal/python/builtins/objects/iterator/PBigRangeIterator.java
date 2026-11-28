/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.builtins.objects.iterator;

import java.math.BigInteger;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.runtime.object.PFactory;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.Shape;

public final class PBigRangeIterator extends PBuiltinIterator {
    private final PInt start;
    private final PInt stop;
    private final PInt step;
    private final PInt len;

    private BigInteger longIndex;

    /*
     * When every value of the range fits a Java long (e.g. range(2**32, 2**32 + n)), the
     * iterator yields Java longs, like any other int in that range, instead of a BigInteger and a
     * PInt per element. In that mode the index is fastIndex and longIndex is unused.
     */
    private final boolean longMode;
    private final long fastStart;
    private final long fastStep;
    private final long fastLen;
    private long fastIndex;

    public PBigRangeIterator(Object clazz, Shape instanceShape, PInt start, PInt stop, PInt step, PInt len) {
        super(clazz, instanceShape);
        this.start = start;
        this.stop = stop;
        this.step = step;
        this.len = len;

        this.longIndex = BigInteger.ZERO;
        long[] fast = fitsLong(start.getValue(), step.getValue(), len.getValue());
        this.longMode = fast != null;
        this.fastStart = fast != null ? fast[0] : 0;
        this.fastStep = fast != null ? fast[1] : 0;
        this.fastLen = fast != null ? fast[2] : 0;
    }

    @TruffleBoundary
    private static long[] fitsLong(BigInteger start, BigInteger step, BigInteger len) {
        if (start.bitLength() > 63 || step.bitLength() > 63 || len.bitLength() > 63) {
            return null;
        }
        if (len.signum() > 0) {
            BigInteger last = start.add(len.subtract(BigInteger.ONE).multiply(step));
            if (last.bitLength() > 63) {
                return null;
            }
        }
        return new long[]{start.longValue(), step.longValue(), len.longValue()};
    }

    public boolean isLongMode() {
        return longMode;
    }

    public boolean hasNextLong() {
        return fastIndex < fastLen;
    }

    public long nextLong() {
        long v = fastStart + fastIndex * fastStep;
        fastIndex++;
        return v;
    }

    private BigInteger currentIndex() {
        return longMode ? BigInteger.valueOf(fastIndex) : longIndex;
    }

    @TruffleBoundary
    public BigInteger getRemainingLength() {
        return this.len.subtract(currentIndex());
    }

    @TruffleBoundary
    public BigInteger nextBigInt() {
        if (longMode) {
            return BigInteger.valueOf(nextLong());
        }
        BigInteger nextVal = start.add(longIndex.multiply(step.getValue()));
        this.longIndex = longIndex.add(BigInteger.ONE);
        return nextVal;
    }

    @TruffleBoundary
    public boolean hasNextBigInt() {
        if (longMode) {
            return hasNextLong();
        }
        return longIndex.compareTo(len.getValue()) < 0;
    }

    public PInt getStart() {
        return start;
    }

    public PInt getStop() {
        return stop;
    }

    public PInt getStep() {
        return step;
    }

    public PInt getLen() {
        return len;
    }

    @TruffleBoundary
    public PInt getLongIndex(PythonLanguage language) {
        return PFactory.createInt(language, currentIndex());
    }

    @TruffleBoundary
    public void setLongIndex(BigInteger idx) {
        if (longMode) {
            // __setstate__ clamps idx below at 0; above len the iterator is exhausted either way
            this.fastIndex = idx.compareTo(len.getValue()) > 0 ? fastLen : idx.longValue();
        } else {
            this.longIndex = idx;
        }
    }
}
