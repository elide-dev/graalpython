/*
 * Copyright (c) 2025, 2026, Oracle and/or its affiliates. All rights reserved.
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
// skip GIL
package com.oracle.graal.python.nodes.instrumentation;

import java.util.ArrayList;
import java.util.List;

import com.oracle.graal.python.nodes.bytecode_dsl.BytecodeDSLCodeUnit.VariableAccess;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.SourceSection;

/** Immutable compiler metadata; safe to query without a language context or the GIL. */
@ExportLibrary(InteropLibrary.class)
public final class VariableAccessDescriptor implements TruffleObject {
    private final String property;
    private final Object names;

    private VariableAccessDescriptor(String property, Object names) {
        this.property = property;
        this.names = names;
    }

    @TruffleBoundary
    public static Object create(String property, boolean write, SourceSection[] sections, List<VariableAccess> accesses) {
        SourceSection section = null;
        for (SourceSection candidate : sections) {
            if (candidate == null || !candidate.isAvailable()) {
                continue;
            }
            for (VariableAccess access : accesses) {
                if (access.write() == write && access.isWithin(candidate) && (section == null || isWithin(candidate, section))) {
                    section = candidate;
                    break;
                }
            }
        }
        if (section == null) {
            return null;
        }
        List<VariableName> names = new ArrayList<>();
        for (VariableAccess access : accesses) {
            if (access.write() == write && access.isWithin(section)) {
                names.add(new VariableName(access.name(), access.getSourceSection(section.getSource())));
            }
        }
        if (names.isEmpty()) {
            return null;
        }
        Object value = names.size() == 1 ? names.get(0) : new NameArray(names.toArray());
        return new VariableAccessDescriptor(property, value);
    }

    private static boolean isWithin(SourceSection candidate, SourceSection outer) {
        return (candidate.getStartLine() > outer.getStartLine() || candidate.getStartLine() == outer.getStartLine() && candidate.getStartColumn() >= outer.getStartColumn()) &&
                        (candidate.getEndLine() < outer.getEndLine() || candidate.getEndLine() == outer.getEndLine() && candidate.getEndColumn() <= outer.getEndColumn());
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new NameArray(new Object[]{property});
    }

    @ExportMessage
    boolean isMemberReadable(String member) {
        return property.equals(member);
    }

    @ExportMessage
    Object readMember(String member) throws UnknownIdentifierException {
        if (!property.equals(member)) {
            throw UnknownIdentifierException.create(member);
        }
        return names;
    }

    @ExportLibrary(InteropLibrary.class)
    static final class NameArray implements TruffleObject {
        private final Object[] values;

        NameArray(Object[] values) {
            this.values = values;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return values.length;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < values.length;
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                throw InvalidArrayIndexException.create(index);
            }
            return values[(int) index];
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class VariableName implements TruffleObject {
        private final String name;
        private final SourceSection section;

        VariableName(String name, SourceSection section) {
            this.name = name;
            this.section = section;
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean isString() {
            return true;
        }

        @ExportMessage
        String asString() {
            return name;
        }

        @ExportMessage
        boolean hasSourceLocation() {
            return section != null && section.isAvailable();
        }

        @ExportMessage
        SourceSection getSourceLocation() throws UnsupportedMessageException {
            if (!hasSourceLocation()) {
                throw UnsupportedMessageException.create();
            }
            return section;
        }
    }
}
