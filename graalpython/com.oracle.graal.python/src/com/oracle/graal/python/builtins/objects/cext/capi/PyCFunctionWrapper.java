/*
 * Copyright (c) 2024, 2026, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.graal.python.builtins.modules.cext.PythonCextBuiltins.checkThrowableBeforeNative;
import static com.oracle.graal.python.runtime.nativeaccess.NativeMemory.NULLPTR;
import static com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.annotations.Builtin;
import com.oracle.graal.python.annotations.CApiUpcallTarget;
import com.oracle.graal.python.annotations.NativeSimpleType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTiming;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.NativeToPythonNode;
import com.oracle.graal.python.builtins.objects.cext.capi.transitions.CApiTransitions.PythonToNativeNewRefNode;
import com.oracle.graal.python.builtins.objects.cext.common.CExtCommonNodes.TransformPExceptionToNativeCachedNode;
import com.oracle.graal.python.builtins.objects.function.PBuiltinFunction;
import com.oracle.graal.python.builtins.objects.function.PKeyword;
import com.oracle.graal.python.builtins.objects.function.Signature;
import com.oracle.graal.python.nodes.argument.CreateArgumentsNode;
import com.oracle.graal.python.nodes.argument.keywords.ExpandKeywordStarargsNode;
import com.oracle.graal.python.nodes.argument.positional.ExecutePositionalStarargsNode;
import com.oracle.graal.python.nodes.call.CallDispatchers.SimpleDirectInvokeNode;
import com.oracle.graal.python.nodes.function.BuiltinFunctionRootNode;
import com.oracle.graal.python.runtime.GilNode;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.nativeaccess.NativeSignature;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;

/**
 * A wrapper class for managed functions such that they can be called with native function pointers
 * (like C type {@code PyCFunction}). This is very similar to {@link TpSlotWrapper} but the main
 * difference is that this wrapper does not keep a reference to the function object but only to the
 * {@link RootCallTarget}
 * <p>
 * Since in C, function pointers are expected to be valid the whole time, the native closure must be
 * kept alive as long as the context lives. Referencing a function object like {@link TpSlotWrapper}
 * does may therefore cause significant memory leaks.
 * </p>
 * <p>
 * The closure entry points only forward to a compiled {@link UpcallRootNode} per
 * wrapper (argument conversion, argument creation with the builtin's defaults and keyword defaults,
 * a direct (inlinable) call of the builtin, result conversion, exception transfer, GIL and the
 * "Java ran" epoch bump), like {@code ExecuteCApiBuiltinRootNode} does for C API builtins. Before,
 * all of it ran through uncached nodes on every call.
 * </p>
 */
public abstract class PyCFunctionWrapper {

    private static final NativeSignature SIGNATURE_1_ARG = NativeSignature.create(NativeSimpleType.POINTER, NativeSimpleType.POINTER);
    private static final NativeSignature SIGNATURE_2_ARG = NativeSignature.create(NativeSimpleType.POINTER, NativeSimpleType.POINTER, NativeSimpleType.POINTER);
    private static final NativeSignature SIGNATURE_3_ARG = NativeSignature.create(NativeSimpleType.POINTER, NativeSimpleType.POINTER, NativeSimpleType.POINTER,
                    NativeSimpleType.POINTER);

    private static final MethodHandle HANDLE_UNARY;
    private static final MethodHandle HANDLE_BINARY;
    private static final MethodHandle HANDLE_VARARGS;
    private static final MethodHandle HANDLE_KEYWORDS;

    static final int KIND_NOARGS = 0;
    static final int KIND_O = 1;
    static final int KIND_VARARGS = 2;
    static final int KIND_KEYWORDS = 3;

    static {
        try {
            HANDLE_UNARY = MethodHandles.lookup().findVirtual(PyCFunctionUnaryWrapper.class,
                            "executeUnary", MethodType.methodType(long.class, long.class));
            HANDLE_BINARY = MethodHandles.lookup().findVirtual(PyCFunctionBinaryWrapper.class,
                            "executeBinary", MethodType.methodType(long.class, long.class, long.class));
            HANDLE_VARARGS = MethodHandles.lookup().findVirtual(PyCFunctionVarargsWrapper.class,
                            "executeVarargs", MethodType.methodType(long.class, long.class, long.class));
            HANDLE_KEYWORDS = MethodHandles.lookup().findVirtual(PyCFunctionKeywordsWrapper.class,
                            "executeKeywords", MethodType.methodType(long.class, long.class, long.class, long.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected final BuiltinFunctionRootNode rootNode;
    protected final Signature signature;
    protected final TruffleString callTargetName;
    protected final CApiTiming timing;
    private final long pointer;

    /**
     * Built-in functions may appear as {@link PyMethodFlags#METH_VARARGS} etc. but we implement them
     * as nodes where the specializations have a fixed arity and then sometimes allow optional
     * arguments (specified with {@link Builtin#minNumOfPositionalArgs()},
     * {@link Builtin#maxNumOfPositionalArgs()}, and similar). This is usually not a problem because
     * if the built-in function is called via the function object, this will provide the correct
     * number of default values. In this case, the call target of the built-in function will be
     * called directly. So, we need to manually provide the number of default values (which is
     * {@link PNone#NO_VALUE} to indicate that the argument is missing).
     */
    protected final Object[] defaults;
    /** Keyword-only defaults of the builtin (as for a call through the function object). */
    protected final PKeyword[] kwDefaults;
    /** Compiled upcall body ({@link UpcallRootNode}). */
    protected final RootCallTarget upcallTarget;

    @SuppressWarnings("this-escape")
    protected PyCFunctionWrapper(BuiltinFunctionRootNode rootNode, Signature signature, Object[] defaults, PKeyword[] kwDefaults, NativeSignature upcallSignature, MethodHandle methodHandle,
                    int kind) {
        assert rootNode != null;
        assert signature != null;
        this.rootNode = rootNode;
        this.signature = signature;
        this.defaults = defaults;
        this.kwDefaults = kwDefaults != null ? kwDefaults : PKeyword.EMPTY_KEYWORDS;
        String ctName = rootNode.getName();
        this.callTargetName = PythonUtils.toTruffleStringUncached(ctName);
        this.timing = CApiTiming.create(false, ctName);
        this.upcallTarget = new UpcallRootNode(PythonLanguage.get(null), this, kind).getCallTarget();
        CApiContext cApiContext = PythonContext.get(null).getCApiContext();
        this.pointer = cApiContext.registerClosure(getClass().getSimpleName(), upcallSignature, methodHandle.bindTo(this), this, getDelegate());
    }

    public final RootCallTarget getCallTarget() {
        return rootNode.getCallTarget();
    }

    public final Object getDelegate() {
        return rootNode.getCallTarget();
    }

    public final long getPointer() {
        return pointer;
    }

    protected abstract String getFlagsRepr();

    @TruffleBoundary
    private static String toString(Object name, String flagsRepr, long pointer) {
        String ptr = pointer != 0 ? " at 0x" + Long.toHexString(pointer) : "";
        return String.format("PyCFunction(%s, %s)%s", name, flagsRepr, ptr);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return PyCFunctionWrapper.toString(callTargetName, getFlagsRepr(), pointer);
    }

    /**
     * Creates a wrapper for a {@link PBuiltinFunction} that can go to native. The flags are
     * required to determine the signature. The resulting {@link PyCFunctionWrapper} will not
     * reference the built-in function object but will only wrap its {@link RootCallTarget}.
     */
    @TruffleBoundary
    public static PyCFunctionWrapper createFromBuiltinFunction(CApiContext cApiContext, PBuiltinFunction builtinFunction) {
        int flags = builtinFunction.getFlags();

        builtinFunction.initCallTarget();
        BuiltinFunctionRootNode root = builtinFunction.getFunctionRootNode();
        Signature signature = builtinFunction.getSignature();
        Object[] defaults = builtinFunction.getDefaults();
        PKeyword[] kwDefaults = builtinFunction.getKwDefaults();
        if (PyMethodFlags.isMethNoArgs(flags)) {
            return cApiContext.getOrCreatePyCFunctionWrapper(root, k -> new PyCFunctionUnaryWrapper(k, signature, defaults, kwDefaults));
        } else if (PyMethodFlags.isMethO(flags)) {
            return cApiContext.getOrCreatePyCFunctionWrapper(root, k -> new PyCFunctionBinaryWrapper(k, signature, defaults, kwDefaults));
        } else if (PyMethodFlags.isMethVarargs(flags)) {
            return cApiContext.getOrCreatePyCFunctionWrapper(root, k -> new PyCFunctionVarargsWrapper(k, signature, defaults, kwDefaults));
        } else if (PyMethodFlags.isMethVarargsWithKeywords(flags)) {
            return cApiContext.getOrCreatePyCFunctionWrapper(root, k -> new PyCFunctionKeywordsWrapper(k, signature, defaults, kwDefaults));
        } else {
            throw shouldNotReachHere("other signature " + Integer.toHexString(flags));
        }
    }

    /**
     * Compiled body of one wrapper's upcall. Arguments: the native pointers (as {@code Long}) the
     * closure received. Returns the new reference (as {@code Long}) or {@code NULLPTR} with the
     * exception set.
     */
    static final class UpcallRootNode extends RootNode {
        private final PyCFunctionWrapper wrapper;
        private final int kind;
        @Child private GilNode gilNode = GilNode.create();
        @Child private NativeToPythonNode toJava0 = NativeToPythonNode.create();
        @Child private NativeToPythonNode toJava1;
        @Child private NativeToPythonNode toJava2;
        @Child private ExecutePositionalStarargsNode starargsNode;
        @Child private DirectCallNode callNode;
        @Child private InvokeBuiltinNode invokeNode = PyCFunctionWrapperFactory.InvokeBuiltinNodeGen.create();
        @Child private PythonToNativeNewRefNode toNative = PythonToNativeNewRefNode.create();
        @Child private TransformPExceptionToNativeCachedNode transformExceptionNode;

        UpcallRootNode(PythonLanguage language, PyCFunctionWrapper wrapper, int kind) {
            super(language);
            this.wrapper = wrapper;
            this.kind = kind;
            this.callNode = Truffle.getRuntime().createDirectCallNode(wrapper.rootNode.getCallTarget());
            if (kind != KIND_NOARGS) {
                toJava1 = NativeToPythonNode.create();
            }
            if (kind == KIND_VARARGS || kind == KIND_KEYWORDS) {
                starargsNode = ExecutePositionalStarargsNode.create();
            }
            if (kind == KIND_KEYWORDS) {
                toJava2 = NativeToPythonNode.create();
            }
        }

        @Override
        public String getName() {
            return wrapper.rootNode.getName();
        }

        @Override
        public String toString() {
            return "PyCFunctionUpcall(" + getName() + ")";
        }

        @Override
        public boolean isCloningAllowed() {
            return false;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            PyCFunctionWrapper w = wrapper;
            boolean wasAcquired = gilNode.acquire();
            CApiTiming.enter();
            try {
                try {
                    Object self = toJava0.execute((long) args[0]);
                    Object[] positional;
                    Object kw = null;
                    switch (kind) {
                        case KIND_NOARGS:
                            positional = PythonUtils.EMPTY_OBJECT_ARRAY;
                            break;
                        case KIND_O:
                            positional = new Object[]{toJava1.execute((long) args[1])};
                            break;
                        case KIND_VARARGS:
                            positional = starargsNode.executeWith(null, toJava1.execute((long) args[1]));
                            break;
                        case KIND_KEYWORDS:
                            positional = starargsNode.executeWith(null, toJava1.execute((long) args[1]));
                            kw = toJava2.execute((long) args[2]);
                            break;
                        default:
                            throw CompilerDirectives.shouldNotReachHere();
                    }
                    Object result = invokeNode.execute(w, callNode, self, positional, kw);
                    return toNative.execute(result);
                } catch (Throwable t) {
                    throw checkThrowableBeforeNative(t, w.toString(), "");
                }
            } catch (PException e) {
                if (transformExceptionNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    transformExceptionNode = insert(TransformPExceptionToNativeCachedNode.create());
                }
                transformExceptionNode.execute(e);
                return NULLPTR;
            } finally {
                // Java ran inside an upcall (validity of C-side dict mirrors)
                CApiEpoch.javaRan(PythonContext.get(this));
                gilNode.release(wasAcquired);
                CApiTiming.exit(w.timing);
            }
        }
    }

    @GenerateInline(false)
    abstract static class InvokeBuiltinNode extends Node {

        abstract Object execute(PyCFunctionWrapper wrapper, DirectCallNode callNode, Object self, Object[] positional, Object kw);

        @Specialization
        static Object doIt(PyCFunctionWrapper wrapper, DirectCallNode callNode, Object self, Object[] positional, Object kw,
                        @Bind Node inliningTarget,
                        @Cached ExpandKeywordStarargsNode expandKeywordsNode,
                        @Cached CreateArgumentsNode createArgumentsNode,
                        @Cached SimpleDirectInvokeNode invokeNode) {
            PKeyword[] keywords = kw == null ? PKeyword.EMPTY_KEYWORDS : expandKeywordsNode.execute(null, inliningTarget, kw);
            Object[] pArgs = createArgumentsNode.execute(inliningTarget, wrapper.callTargetName, positional, keywords, wrapper.signature, self, null, wrapper.defaults, wrapper.kwDefaults,
                            false);
            return invokeNode.executeBuiltin(null, inliningTarget, callNode, pArgs);
        }
    }

    public static final class PyCFunctionUnaryWrapper extends PyCFunctionWrapper {

        PyCFunctionUnaryWrapper(BuiltinFunctionRootNode root, Signature signature, Object[] defaults, PKeyword[] kwDefaults) {
            super(root, signature, defaults, kwDefaults, SIGNATURE_1_ARG, HANDLE_UNARY, KIND_NOARGS);
        }

        @CApiUpcallTarget
        public long executeUnary(long arg0) {
            return (long) upcallTarget.call(arg0);
        }

        @Override
        protected String getFlagsRepr() {
            return "METH_NOARGS";
        }
    }

    public static final class PyCFunctionBinaryWrapper extends PyCFunctionWrapper {

        PyCFunctionBinaryWrapper(BuiltinFunctionRootNode root, Signature signature, Object[] defaults, PKeyword[] kwDefaults) {
            super(root, signature, defaults, kwDefaults, SIGNATURE_2_ARG, HANDLE_BINARY, KIND_O);
        }

        @CApiUpcallTarget
        public long executeBinary(long arg0, long arg1) {
            return (long) upcallTarget.call(arg0, arg1);
        }

        @Override
        protected String getFlagsRepr() {
            return "METH_O";
        }
    }

    public static final class PyCFunctionVarargsWrapper extends PyCFunctionWrapper {

        PyCFunctionVarargsWrapper(BuiltinFunctionRootNode root, Signature signature, Object[] defaults, PKeyword[] kwDefaults) {
            super(root, signature, defaults, kwDefaults, SIGNATURE_2_ARG, HANDLE_VARARGS, KIND_VARARGS);
        }

        @CApiUpcallTarget
        public long executeVarargs(long arg0, long arg1) {
            return (long) upcallTarget.call(arg0, arg1);
        }

        @Override
        protected String getFlagsRepr() {
            return "METH_VARARGS";
        }
    }

    public static final class PyCFunctionKeywordsWrapper extends PyCFunctionWrapper {

        PyCFunctionKeywordsWrapper(BuiltinFunctionRootNode root, Signature signature, Object[] defaults, PKeyword[] kwDefaults) {
            super(root, signature, defaults, kwDefaults, SIGNATURE_3_ARG, HANDLE_KEYWORDS, KIND_KEYWORDS);
        }

        @CApiUpcallTarget
        public long executeKeywords(long arg0, long arg1, long arg2) {
            return (long) upcallTarget.call(arg0, arg1, arg2);
        }

        @Override
        protected String getFlagsRepr() {
            return "METH_KEYWORDS";
        }
    }
}
