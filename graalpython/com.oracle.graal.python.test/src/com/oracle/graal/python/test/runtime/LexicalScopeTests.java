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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.Reference;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.graalvm.polyglot.Context;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.source.Source;

public class LexicalScopeTests {
    private static final String ID = "python-lexical-scope-tests";

    @Test
    public void uncalledFunctions() throws IOException {
        for (boolean forceSections : new boolean[]{false, true}) {
            for (boolean eagerNodes : new boolean[]{false, true}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (Context context = Context.newBuilder("python").allowAllAccess(true).allowExperimentalOptions(true).option("python.ForceInitializeSourceSections", Boolean.toString(
                                forceSections)).option("python.EagerlyMaterializeInstrumentationNodes", Boolean.toString(eagerNodes)).out(output).err(output).build()) {
                    AnalysisInstrument instrument = context.getEngine().getInstruments().get(ID).lookup(AnalysisInstrument.class);
                    context.initialize("python");
                    output.reset();
                    context.enter();
                    try {
                        Map<String, Set<String>> scopes = instrument.analyze(Source.newBuilder("python", """
                                        def inspect(outer):
                                            inner = outer
                                            return inner
                                        print('must not execute')
                                        raise RuntimeError('must not execute')
                                        """, "lexical.py").uri(URI.create("file:///lexical.py")).build());
                        assertEquals(Set.of("outer", "inner"), scopes.get("inspect"));
                        scopes = instrument.analyze(Source.newBuilder("python", """
                                        def enclosing(arg):
                                            captured = arg
                                            def nested(param):
                                                nonlocal captured
                                                global external
                                                before = later
                                                later = param
                                                captured = later
                                                return captured, arg
                                            return nested
                                        raise RuntimeError('must not execute')
                                        """, "lexical.py").uri(URI.create("file:///lexical.py")).build());
                        assertEquals(Set.of("arg", "captured", "nested"), scopes.get("enclosing"));
                        assertEquals(Set.of("param", "before", "later", "captured", "arg"), scopes.get("nested"));
                        assertFalse(scopes.containsKey("inspect"));
                        scopes = instrument.analyze(Source.newBuilder("python", """
                                        def generator(items):
                                            for item in items:
                                                yield item
                                        async def coroutine(π):
                                            result = π
                                            return result
                                        class Example:
                                            def method(self):
                                                return __class__
                                        def comprehension(items):
                                            return (element for element in items)
                                        raise RuntimeError('must not execute')
                                        """, "other.py").build());
                        assertEquals(Set.of("items", "item"), scopes.get("generator"));
                        assertEquals(Set.of("π", "result"), scopes.get("coroutine"));
                        assertEquals(Set.of("self", "__class__"), scopes.get("method"));
                        assertEquals(Set.of("element"), scopes.get("<genexpr>"));
                        assertEquals("", output.toString());
                    } finally {
                        context.leave();
                    }
                }
            }
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

        Map<String, Set<String>> analyze(Source source) throws IOException {
            Map<String, Set<String>> scopes = new HashMap<>();
            SourceSectionFilter filter = SourceSectionFilter.newBuilder().sourceIs(source).tagIs(StandardTags.StatementTag.class, StandardTags.RootTag.class).build();
            var binding = env.getInstrumenter().attachLoadSourceSectionListener(filter, event -> {
                try {
                    InteropLibrary interop = InteropLibrary.getUncached();
                    for (boolean nodeEnter : new boolean[]{true, false}) {
                        assertTrue(NodeLibrary.getUncached().hasScope(event.getNode(), null));
                        Object scope = NodeLibrary.getUncached().getScope(event.getNode(), null, nodeEnter);
                        assertTrue(interop.isScope(scope));
                        assertFalse(interop.hasScopeParent(scope));
                        assertThrows(UnsupportedMessageException.class, () -> interop.getScopeParent(scope));
                        assertEquals(source, interop.getSourceLocation(scope).getSource());
                        Object members = interop.getMembers(scope);
                        Set<String> names = new HashSet<>();
                        for (long i = 0; i < interop.getArraySize(members); i++) {
                            String name = interop.asString(interop.readArrayElement(members, i));
                            assertTrue(names.add(name));
                            assertFalse(interop.isMemberReadable(scope, name));
                            assertFalse(interop.isMemberModifiable(scope, name));
                            assertFalse(interop.isMemberInsertable(scope, name));
                            assertThrows(UnsupportedMessageException.class, () -> interop.readMember(scope, name));
                            assertThrows(UnsupportedMessageException.class, () -> interop.writeMember(scope, name, 42));
                        }
                        String rootName = event.getNode().getRootNode().getName();
                        Set<String> previous = scopes.put(rootName, names);
                        if (previous != null) {
                            assertEquals(previous, names);
                        }
                    }
                } catch (InteropException e) {
                    throw new AssertionError(e);
                }
            }, true);
            CallTarget target;
            try {
                target = env.parse(source);
            } finally {
                binding.dispose();
            }
            Reference.reachabilityFence(target);
            return scopes;
        }
    }
}
