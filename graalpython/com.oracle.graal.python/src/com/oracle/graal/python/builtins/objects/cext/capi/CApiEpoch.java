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
package com.oracle.graal.python.builtins.objects.cext.capi;

import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.nativeaccess.NativeMemory;

/**
 * The "Java ran" epoch: one native word that is bumped whenever Java code may have run since C last
 * looked at it, i.e. on entry of every downcall, on exit of every upcall (generated C API builtin
 * wrappers, slot and method closures through {@code GilNode.UncachedAcquire}) and whenever a thread
 * (re)acquires the GIL. Managed objects can only change while Java runs, so a C-side cache derived
 * from managed state (the {@code PyDict_Next} entry mirror in {@code dictobject.c}) stays valid
 * while the epoch is unchanged.
 *
 * <p>
 * The word is allocated when C first asks for its address ({@link PythonContext#getCApiEpochAddress});
 * until then there is no C-side cache and bumping is a no-op.
 * </p>
 */
public final class CApiEpoch {

    private CApiEpoch() {
    }

    /** Allocates the epoch word; the first value is 1 so that a zero stamp never matches. */
    public static long allocate() {
        long p = NativeMemory.malloc(8);
        NativeMemory.writeLong(p, 1);
        return p;
    }

    /** Java code may have run: invalidates every C-side cache stamped with the current epoch. */
    public static void javaRan(PythonContext context) {
        long p = context.peekCApiEpochAddress();
        if (p != 0) {
            NativeMemory.writeLong(p, NativeMemory.readLong(p) + 1);
        }
    }

    public static void javaRan() {
        javaRan(PythonContext.get(null));
    }

    /** The current epoch, or 0 if C has not asked for it yet. */
    public static long read(PythonContext context) {
        long p = context.peekCApiEpochAddress();
        return p == 0 ? 0 : NativeMemory.readLong(p);
    }
}
