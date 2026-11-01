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
package com.oracle.graal.python.test.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.Reference;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.graal.python.builtins.objects.code.PCode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags.ReadVariableTag;
import com.oracle.truffle.api.instrumentation.StandardTags.WriteVariableTag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

public class VariableAccessTests {
    private static final String ID = "python-variable-access-tests";

    private record Access(String root, String name, boolean write, int line, int column, String text) {
    }

    @Test
    public void unexecutedVariableAccesses() throws Exception {
        for (boolean forceSections : new boolean[]{false, true}) {
            for (boolean eagerNodes : new boolean[]{false, true}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (Context context = Context.newBuilder("python").allowAllAccess(true).allowExperimentalOptions(true).option("python.ForceInitializeSourceSections", Boolean.toString(
                                forceSections)).option("python.EagerlyMaterializeInstrumentationNodes", Boolean.toString(eagerNodes)).out(output).err(output).build()) {
                    AnalysisInstrument instrument = context.getEngine().getInstruments().get(ID).lookup(AnalysisInstrument.class);
                    context.initialize("python");
                    output.reset();
                    Analysis analysis;
                    context.enter();
                    try {
                        analysis = instrument.analyze(Source.newBuilder("python", """
                                        def inspect(outer):
                                            inner = outer
                                            inner += outer
                                            left, right = outer
                                            return inner, left, right
                                        @must_not_execute()
                                        def poison(arg: must_not_execute() = must_not_execute()):
                                            return arg
                                        import must_not_import
                                        print('must not execute')
                                        raise RuntimeError('must not execute')
                                        """, "accesses.py").uri(URI.create("file:///accesses.py")).build());
                        List<Access> accesses = analysis.accesses().stream().filter(a -> a.root().equals("inspect")).toList();
                        assertEquals(11, accesses.size());
                        assertTrue(accesses.contains(new Access("inspect", "inner", true, 2, 5, "inner")));
                        assertTrue(accesses.contains(new Access("inspect", "outer", false, 2, 13, "outer")));
                        assertTrue(accesses.contains(new Access("inspect", "inner", true, 3, 5, "inner")));
                        assertTrue(accesses.contains(new Access("inspect", "inner", false, 3, 5, "inner")));
                        assertTrue(accesses.contains(new Access("inspect", "left", true, 4, 5, "left")));
                        assertTrue(accesses.contains(new Access("inspect", "right", true, 4, 11, "right")));
                        Analysis edited = instrument.analyze(Source.newBuilder("python", """
                                        def changed(value):
                                            return value
                                        """, "accesses.py").uri(URI.create("file:///accesses.py")).build());
                        assertEquals(List.of(new Access("changed", "value", false, 2, 12, "value")), edited.accesses());
                        assertEquals("", output.toString());
                    } finally {
                        context.leave();
                    }
                    // Both node-object construction and interop reads must work without an entered
                    // context, concurrently with other metadata readers.
                    CompletableFuture<List<Access>> first = CompletableFuture.supplyAsync(analysis::accesses);
                    CompletableFuture<List<Access>> second = CompletableFuture.supplyAsync(analysis::accesses);
                    assertEquals(first.get(), second.get());
                }
            }
        }
    }

    @Test
    public void bindingNamesAndAssignmentForms() throws IOException {
        try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
            AnalysisInstrument instrument = context.getEngine().getInstruments().get(ID).lookup(AnalysisInstrument.class);
            context.initialize("python");
            context.enter();
            try {
                Analysis analysis = instrument.analyze(Source.newBuilder("python", """
                                def enclosing(captured):
                                    def nested(obj, seq, index):
                                        nonlocal captured
                                        global shared
                                        captured = shared
                                        obj.attribute = seq[index]
                                        return (local := captured)
                                    return nested
                                class Example:
                                    def method(self, __private):
                                        K = __private
                                        return K
                                """, "bindings.py").build());
                List<Access> accesses = analysis.accesses();
                assertTrue(accesses.stream().anyMatch(a -> a.root().equals("nested") && a.name().equals("captured") && a.write()));
                assertTrue(accesses.stream().anyMatch(a -> a.name().equals("shared") && !a.write()));
                assertTrue(accesses.stream().anyMatch(a -> a.name().equals("local") && a.write()));
                assertFalse(accesses.stream().anyMatch(a -> a.name().equals("attribute")));
                assertTrue(accesses.stream().anyMatch(a -> a.name().equals("_Example__private") && a.text().equals("__private")));
                assertTrue(accesses.stream().anyMatch(a -> a.name().equals("K") && a.text().equals("K") && a.write()));
                Analysis unicode = instrument.analyze(Source.newBuilder("python",
                                "def unicode(arg):\r\n    emoji = '😀'; 𐐀 = arg; return 𐐀\r\n", "unicode.py").build());
                assertTrue(unicode.accesses().stream().anyMatch(a -> a.name().equals("𐐀") && a.text().equals("𐐀") && a.write()));
                assertTrue(unicode.accesses().stream().anyMatch(a -> a.name().equals("𐐀") && a.text().equals("𐐀") && !a.write()));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    public void executedTagsPreservePythonBehavior() throws IOException {
        try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
            AnalysisInstrument instrument = context.getEngine().getInstruments().get(ID).lookup(AnalysisInstrument.class);
            context.initialize("python");
            context.enter();
            try {
                Analysis analysis = instrument.analyzeExecuting(Source.newBuilder("python", """
                                def run(pair):
                                    left, right = pair
                                    total = 0
                                    for value in (left, right):
                                        total += value
                                    return total
                                assert run((3, 4)) == 7
                                assert [x + 1 for x in range(2)] == [1, 2]
                                """, "execution.py").build());
                assertTrue(analysis.accesses().stream().anyMatch(a -> a.name().equals("value") && a.write()));
                assertTrue(analysis.accesses().stream().anyMatch(a -> a.name().equals("x") && a.write()));
                assertFalse(analysis.accesses().stream().anyMatch(a -> a.name().startsWith(".")));
            } finally {
                context.leave();
            }
        }
    }

    @Test
    public void serializedCodeRetainsMetadata() throws IOException {
        try (Context context = Context.newBuilder("python").allowAllAccess(true).build()) {
            AnalysisInstrument instrument = context.getEngine().getInstruments().get(ID).lookup(AnalysisInstrument.class);
            context.initialize("python");
            context.enter();
            try {
                Source source = Source.newBuilder("python", """
                                def serialized(arg):
                                    result = arg
                                    return result
                                """, "serialized.py").build();
                Analysis analysis = instrument.analyzeSerialized(source);
                assertEquals(6, analysis.accesses().stream().filter(a -> a.root().equals("serialized")).count());
            } finally {
                context.leave();
            }
        }
    }

    private record Analysis(CallTarget target, List<Node> nodes) {
        List<Access> accesses() {
            List<Access> result = new ArrayList<>();
            InteropLibrary interop = InteropLibrary.getUncached();
            for (Node node : nodes) {
                boolean write = ((InstrumentableNode) node).hasTag(WriteVariableTag.class);
                String property = write ? WriteVariableTag.NAME : ReadVariableTag.NAME;
                Object descriptor = ((InstrumentableNode) node).getNodeObject();
                assertNotNull(descriptor);
                assertTrue(interop.hasMembers(descriptor));
                assertTrue(interop.isMemberReadable(descriptor, property));
                assertFalse(interop.isMemberModifiable(descriptor, property));
                assertFalse(interop.isMemberInsertable(descriptor, "other"));
                assertThrows(UnknownIdentifierException.class, () -> interop.readMember(descriptor, "other"));
                try {
                    Object members = interop.getMembers(descriptor);
                    assertEquals(1, interop.getArraySize(members));
                    assertEquals(property, interop.asString(interop.readArrayElement(members, 0)));
                    assertFalse(interop.isArrayElementReadable(members, -1));
                    assertThrows(InvalidArrayIndexException.class, () -> interop.readArrayElement(members, -1));
                    Object names = interop.readMember(descriptor, property);
                    if (interop.hasArrayElements(names)) {
                        assertFalse(interop.isArrayElementReadable(names, -1));
                        assertThrows(InvalidArrayIndexException.class, () -> interop.readArrayElement(names, interop.getArraySize(names)));
                        for (long i = 0; i < interop.getArraySize(names); i++) {
                            result.add(access(node, write, interop.readArrayElement(names, i), interop));
                        }
                    } else {
                        result.add(access(node, write, names, interop));
                    }
                } catch (InteropException e) {
                    throw new AssertionError(e);
                }
            }
            Reference.reachabilityFence(target);
            return result;
        }

        private static Access access(Node node, boolean write, Object name, InteropLibrary interop) throws InteropException {
            assertTrue(interop.isString(name));
            assertTrue(interop.hasSourceLocation(name));
            SourceSection section = interop.getSourceLocation(name);
            assertEquals(node.getSourceSection().getSource(), section.getSource());
            return new Access(node.getRootNode().getName(), interop.asString(name), write, section.getStartLine(), section.getStartColumn(), section.getSource().hasCharacters()
                            ? section.getCharacters().toString()
                            : "");
        }
    }

    @TruffleInstrument.Registration(id = ID, services = AnalysisInstrument.class)
    public static final class AnalysisInstrument extends TruffleInstrument {
        private Env env;

        @Override
        protected void onCreate(Env instrumentEnv) {
            env = instrumentEnv;
            env.registerService(this);
        }

        Analysis analyze(Source source) throws IOException {
            return analyze(source, false, false);
        }

        Analysis analyzeSerialized(Source source) throws IOException {
            return analyze(source, true, true);
        }

        Analysis analyzeExecuting(Source source) throws IOException {
            return analyze(source, true, false);
        }

        private Analysis analyze(Source source, boolean execute, boolean serialized) throws IOException {
            List<Node> nodes = new ArrayList<>();
            SourceSectionFilter filter = SourceSectionFilter.newBuilder().sourceIs(source).tagIs(ReadVariableTag.class, WriteVariableTag.class).build();
            if (serialized) {
                // marshal reconstructs an unloaded Source named after the code's qualified
                // name, rather than retaining the original in-memory Source identity.
                filter = SourceSectionFilter.newBuilder().rootNameIs(name -> "serialized".equals(name)).tagIs(ReadVariableTag.class, WriteVariableTag.class).build();
            }
            var binding = env.getInstrumenter().attachLoadSourceSectionListener(filter, event -> nodes.add(event.getNode()), true);
            CallTarget target;
            try {
                target = env.parse(source);
                if (execute) {
                    target.call();
                }
                if (serialized) {
                    // This fixture intentionally executes harmless definitions and a cloned
                    // function. The poison-source test exercises the inert path separately.
                    env.parse(Source.newBuilder("python", """
                                    import marshal, types
                                    cloned = types.FunctionType(marshal.loads(marshal.dumps(serialized.__code__)), globals())
                                    assert cloned(42) == 42
                                    """, "roundtrip.py").build()).call();
                    PCode original = (PCode) env.parse(Source.newBuilder("python", "serialized.__code__", "original-code.py").build()).call();
                    PCode cloned = (PCode) env.parse(Source.newBuilder("python", "cloned.__code__", "cloned-code.py").build()).call();
                    assertEquals(original.getCodeUnit().variableAccesses, cloned.getCodeUnit().variableAccesses);
                }
            } finally {
                binding.dispose();
            }
            return new Analysis(target, List.copyOf(nodes));
        }
    }
}
