/*
 * Copyright (c) 2017, 2025, Oracle and/or its affiliates.
 * Copyright (c) 2014, Regents of the University of California
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

package com.oracle.graal.python.builtins.objects.floats;

import com.oracle.truffle.api.object.Shape;

/**
 * In-flight value copy of an instance of a native subclass of
 * {@code float} whose instances are nothing but (type, {@code ob_fval}) (see
 * {@code NativeSmall.isFloatValueType}). The class is the native type. {@code structHint} is the
 * struct this value was last converted from or to (0 if none) and {@code structGen} its pool generation
 * word at that time; it is a hint only: before reuse it is checked to be the same incarnation (generation
 * unchanged) and alive with the same type and bits. Not counted: the struct may be freed under it.
 */
public final class PNativeFloat extends PFloat {

    private long structHint;
    private long structGen;

    public PNativeFloat(Object clazz, Shape instanceShape, double value, long structHint, long structGen) {
        super(clazz, instanceShape, value);
        this.structHint = structHint;
        this.structGen = structGen;
    }

    public long getStructHint() {
        return structHint;
    }

    public long getStructGen() {
        return structGen;
    }

    public void setStructHint(long p, long gen) {
        this.structHint = p;
        this.structGen = gen;
    }
}
