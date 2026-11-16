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

import com.oracle.graal.python.nodes.bytecode_dsl.BytecodeDSLCodeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.bytecode.Instruction.Argument;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.TagTree;
import com.oracle.truffle.api.bytecode.TagTreeNode;
import com.oracle.truffle.api.instrumentation.StandardTags.ReadVariableTag;
import com.oracle.truffle.api.instrumentation.StandardTags.WriteVariableTag;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * Node object of a {@link ReadVariableTag} or {@link WriteVariableTag} region. Nothing is recorded
 * for it at compile time: the names are resolved on demand from the operands of the load or store
 * instruction in the tag's bytecode range, against the names the code unit already holds
 * ({@code varnames}, {@code cellvars}, {@code freevars} and name constants). The identifier
 * locations are the tag's source layers emitted by the compiler: the enclosing statement first,
 * then one identifier range per variable operand.
 * <p>
 * Instances are immutable and safe to create and query without a language context or the GIL.
 */
@ExportLibrary(InteropLibrary.class)
public final class VariableAccessDescriptor implements TruffleObject {
    private final String property;
    private final Object names;

    private VariableAccessDescriptor(String property, Object names) {
        this.property = property;
        this.names = names;
    }

    @TruffleBoundary
    public static Object create(TagTreeNode node, BytecodeDSLCodeUnit code) {
        boolean write;
        if (node.hasTag(WriteVariableTag.class)) {
            write = true;
        } else if (node.hasTag(ReadVariableTag.class)) {
            write = false;
        } else {
            return null;
        }
        BytecodeNode bytecode = node.getBytecodeNode();
        if (bytecode == null) {
            return null;
        }
        List<TagTree> nested = new ArrayList<>();
        collectNestedVariableTags(node, nested);

        // Operands are emitted before the operation that consumes them, so the access is the last
        // matching instruction of this region outside nested variable tags. Cell stores receive
        // the cell from the region's first local load instead of an index operand.
        Instruction access = null;
        Instruction firstLocalLoad = null;
        int returnIndex = node.getReturnBytecodeIndex();
        for (int bci = node.getEnterBytecodeIndex(); bci <= returnIndex;) {
            TagTree skip = enclosing(nested, bci);
            if (skip != null) {
                bci = bytecode.getInstruction(skip.getReturnBytecodeIndex()).getNextBytecodeIndex();
                continue;
            }
            Instruction instruction = bytecode.getInstruction(bci);
            String name = baseName(instruction);
            if (firstLocalLoad == null && name.equals("load.local")) {
                firstLocalLoad = instruction;
            }
            if (isAccess(name, write)) {
                access = instruction;
            }
            bci = instruction.getNextBytecodeIndex();
        }
        if (access == null) {
            return null;
        }

        String[] accessNames = resolveNames(code, bytecode, access, baseName(access), firstLocalLoad);
        SourceSection[] sections = node.getSourceSections();
        List<VariableName> result = new ArrayList<>(accessNames.length);
        for (int i = 0; i < accessNames.length; i++) {
            if (accessNames[i] != null) {
                // Layer 0 is the statement; identifier layers follow in operand order.
                SourceSection section = sections != null && i + 1 < sections.length ? sections[i + 1] : null;
                result.add(new VariableName(accessNames[i], section));
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        Object value = result.size() == 1 ? result.get(0) : new NameArray(result.toArray());
        return new VariableAccessDescriptor(write ? WriteVariableTag.NAME : ReadVariableTag.NAME, value);
    }

    private static void collectNestedVariableTags(TagTree tree, List<TagTree> result) {
        for (TagTree child : tree.getTreeChildren()) {
            if (child.hasTag(ReadVariableTag.class) || child.hasTag(WriteVariableTag.class)) {
                result.add(child);
            } else {
                collectNestedVariableTags(child, result);
            }
        }
    }

    private static TagTree enclosing(List<TagTree> trees, int bci) {
        for (TagTree tree : trees) {
            if (bci >= tree.getEnterBytecodeIndex() && bci <= tree.getReturnBytecodeIndex()) {
                return tree;
            }
        }
        return null;
    }

    /** The instruction name without quickening or boxing-elimination suffixes. */
    private static String baseName(Instruction instruction) {
        String name = instruction.getName();
        int quickening = name.indexOf('$');
        return quickening < 0 ? name : name.substring(0, quickening);
    }

    private static boolean isAccess(String name, boolean write) {
        if (write) {
            return switch (name) {
                case "store.local", "c.StoreCell", "c.WriteGlobal", "c.WriteName", "c.ForIterate", "c.UnpackToLocals2" -> true;
                default -> false;
            };
        } else {
            return switch (name) {
                case "load.local", "c.LoadCell", "c.LoadFromDictOrCell", "c.ReadGlobal", "c.LoadFromDictOrGlobals", "c.ReadName" -> true;
                default -> false;
            };
        }
    }

    /** One entry per variable operand of the access; {@code null} for compiler temporaries. */
    private static String[] resolveNames(BytecodeDSLCodeUnit code, BytecodeNode bytecode, Instruction access, String name, Instruction firstLocalLoad) {
        switch (name) {
            case "c.StoreCell":
                return firstLocalLoad == null ? new String[0] : resolveNames(code, bytecode, firstLocalLoad, "load.local", null);
            case "c.LoadCell":
            case "c.LoadFromDictOrCell":
                for (Argument argument : access.getArguments()) {
                    Integer index = intArgument(argument);
                    if (index != null) {
                        return new String[]{cellOrFreeName(code, index)};
                    }
                }
                return new String[0];
            case "c.WriteGlobal":
            case "c.WriteName":
            case "c.ReadGlobal":
            case "c.LoadFromDictOrGlobals":
            case "c.ReadName":
                for (Argument argument : access.getArguments()) {
                    if (argument.getKind() == Argument.Kind.CONSTANT && argument.asConstant() instanceof TruffleString constant) {
                        return new String[]{constant.toJavaStringUncached()};
                    }
                }
                return new String[0];
            default:
                // load.local and store.local encode the frame slot; c.ForIterate and
                // c.UnpackToLocals2 take local accessors, whose info is the varnames index.
                List<String> names = new ArrayList<>(2);
                for (Argument argument : access.getArguments()) {
                    if (argument.getKind() == Argument.Kind.LOCAL_OFFSET) {
                        names.add(slotName(code, argument.asLocalOffset()));
                    } else if (argument.getKind() == Argument.Kind.CONSTANT && argument.asConstant() instanceof LocalAccessor local) {
                        names.add(local.getLocalInfo(bytecode) instanceof Integer index && index < code.varnames.length ? code.varnames[index].toJavaStringUncached() : null);
                    }
                }
                return names.toArray(new String[0]);
        }
    }

    private static Integer intArgument(Argument argument) {
        return switch (argument.getKind()) {
            case INTEGER -> argument.asInteger();
            case CONSTANT -> argument.asConstant() instanceof Integer i ? i : null;
            default -> null;
        };
    }

    /** Frame slot layout: varnames, then cellvars, then freevars, then compiler temporaries. */
    private static String slotName(BytecodeDSLCodeUnit code, int slot) {
        if (slot < 0) {
            return null;
        } else if (slot < code.varnames.length) {
            return code.varnames[slot].toJavaStringUncached();
        }
        return cellOrFreeName(code, slot - code.varnames.length);
    }

    private static String cellOrFreeName(BytecodeDSLCodeUnit code, int index) {
        if (index < 0) {
            return null;
        } else if (index < code.cellvars.length) {
            return code.cellvars[index].toJavaStringUncached();
        } else if (index - code.cellvars.length < code.freevars.length) {
            return code.freevars[index - code.cellvars.length].toJavaStringUncached();
        }
        return null;
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
