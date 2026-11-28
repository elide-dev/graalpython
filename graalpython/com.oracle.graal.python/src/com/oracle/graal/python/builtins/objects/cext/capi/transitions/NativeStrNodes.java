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
package com.oracle.graal.python.builtins.objects.cext.capi.transitions;

import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;

import com.oracle.graal.python.runtime.nativeaccess.NativeComplex;
import com.oracle.graal.python.runtime.nativeaccess.NativeStr;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;

/** Conversions between Java strings and native str structs (see {@link NativeStr}). */
public final class NativeStrNodes {

    private NativeStrNodes() {
    }

    /**
     * A native str struct with the value of {@code s} and one count: owned by C with
     * {@code newRef}, otherwise held on the borrowed stack until the outermost downcall returns.
     */
    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class StrToNativeNode extends Node {

        public abstract long execute(Node inliningTarget, long st, TruffleString s, boolean newRef);

        @Specialization
        static long doIt(Node inliningTarget, long st, TruffleString s, boolean newRef,
                        @Cached InlinedConditionProfile asciiProfile,
                        @Cached(inline = false) TruffleString.GetCodeRangeNode getCodeRangeNode,
                        @Cached(inline = false) TruffleString.CodePointLengthNode lengthNode,
                        @Cached(inline = false) TruffleString.SwitchEncodingNode switchEncodingNode,
                        @Cached(inline = false) TruffleString.CopyToNativeMemoryNode copyNode) {
            CompilerAsserts.partialEvaluationConstant(newRef);
            long prev = NativeStr.lastBorrowed(st, s);
            if (prev != 0) {
                if (newRef) {
                    NativeStr.incref(prev);   // C's count; the borrowed stack already holds one
                }
                return prev;
            }
            TruffleString.CodeRange range = getCodeRangeNode.execute(s, TS_ENCODING);
            int length = lengthNode.execute(s, TS_ENCODING);
            int kind;
            boolean ascii;
            TruffleString.Encoding enc;
            if (asciiProfile.profile(inliningTarget, range == TruffleString.CodeRange.ASCII)) {
                kind = 1;
                ascii = true;
                enc = TruffleString.Encoding.US_ASCII;
            } else if (range == TruffleString.CodeRange.LATIN_1) {
                kind = 1;
                ascii = false;
                enc = TruffleString.Encoding.ISO_8859_1;
            } else if (range == TruffleString.CodeRange.BMP) {
                kind = 2;
                ascii = false;
                enc = TruffleString.Encoding.UTF_16;
            } else {
                // VALID (astral) and BROKEN (lone surrogates; same as the stub path's choice)
                kind = 4;
                ascii = false;
                enc = TruffleString.Encoding.UTF_32;
            }
            long p = NativeStr.allocate(st, length, kind, ascii);
            if (length > 0) {
                TruffleString converted = switchEncodingNode.execute(s, enc);
                copyNode.execute(converted, 0, p, ascii ? (int) NativeStr.ASCII_HEADER : (int) NativeStr.COMPACT_HEADER, length * kind, enc);
            }
            NativeStr.remember(p, s);
            /*
             * A borrowed copy holds its one count on the borrowed stack. A new-reference copy gets one
             * more count there: C code may drop its new reference and keep using the pointer because
             * another holder (a list, a dict, an attribute) keeps the object alive in CPython (getargs.c
             * does this with PySequence_GetItem); here the holder has the Java string, not this copy,
             * so the copy stays valid until the outermost downcall returns, like a borrowed argument.
             */
            if (newRef) {
                NativeStr.incref(p);
                NativeComplex.borrowPush(st, p);
            } else {
                NativeComplex.borrowPush(st, p);
                NativeStr.setLastBorrowed(st, s, p);
            }
            return p;
        }
    }

    /** The Java value of a native str struct; with {@code transfer} drops C's count. */
    @GenerateInline
    @GenerateCached(false)
    @GenerateUncached
    public abstract static class NativeToStrNode extends Node {

        public abstract TruffleString execute(Node inliningTarget, long p, boolean transfer);

        @Specialization
        static TruffleString doIt(Node inliningTarget, long p, boolean transfer,
                        @Cached InlinedConditionProfile hitProfile,
                        @Cached(inline = false) TruffleString.FromNativePointerWithCompactionUTF32Node fromNativeNode) {
            TruffleString s = NativeStr.recall(p);
            if (!hitProfile.profile(inliningTarget, s != null)) {
                int kind = NativeStr.kind(p);
                long length = NativeStr.length(p);
                TruffleString.CompactionLevel level = kind == 1 ? TruffleString.CompactionLevel.S1 : kind == 2 ? TruffleString.CompactionLevel.S2 : TruffleString.CompactionLevel.S4;
                s = fromNativeNode.execute(NativeStr.data(p), 0, (int) (length * kind), level, true);
                NativeStr.remember(p, s);
                NativeStr.count(NativeStr.C_C_TO_JAVA, 1);
            }
            if (transfer) {
                NativeStr.decref(p);
            }
            return s;
        }
    }
}
