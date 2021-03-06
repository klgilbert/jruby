/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006-2007 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2006-2007 Charles Nutter <headius@headius.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jruby.anno.FrameField;
import org.jruby.runtime.callsite.LtCallSite;
import org.jruby.runtime.callsite.LeCallSite;
import org.jruby.runtime.callsite.MinusCallSite;
import org.jruby.runtime.callsite.MulCallSite;
import org.jruby.runtime.callsite.NormalCachingCallSite;
import org.jruby.runtime.callsite.GtCallSite;
import org.jruby.runtime.callsite.PlusCallSite;
import org.jruby.runtime.callsite.GeCallSite;
import org.jruby.RubyInstanceConfig;
import org.jruby.runtime.callsite.CmpCallSite;
import org.jruby.runtime.callsite.EqCallSite;
import org.jruby.runtime.callsite.BitAndCallSite;
import org.jruby.runtime.callsite.BitOrCallSite;
import org.jruby.runtime.callsite.FunctionalCachingCallSite;
import org.jruby.runtime.callsite.RespondToCallSite;
import org.jruby.runtime.callsite.ShiftLeftCallSite;
import org.jruby.runtime.callsite.ShiftRightCallSite;
import org.jruby.runtime.callsite.SuperCallSite;
import org.jruby.runtime.callsite.VariableCachingCallSite;
import org.jruby.runtime.callsite.XorCallSite;
import org.jruby.runtime.invokedynamic.MethodNames;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

/**
 *
 * @author headius
 */
public class MethodIndex {
    private static final boolean DEBUG = false;

    private static final Logger LOG = LoggerFactory.getLogger(MethodIndex.class);

    @Deprecated
    public static final int NO_METHOD = MethodNames.DUMMY.ordinal();
    @Deprecated
    public static final int OP_EQUAL = MethodNames.OP_EQUAL.ordinal();
    @Deprecated
    public static final int EQL = MethodNames.EQL.ordinal();
    @Deprecated
    public static final int HASH = MethodNames.HASH.ordinal();
    @Deprecated
    public static final int OP_CMP = MethodNames.OP_CMP.ordinal();
    @Deprecated
    public static final int MAX_METHODS = MethodNames.values().length;

    @Deprecated
    public static final String[] METHOD_NAMES = {
        "",
        "==",
        "eql?",
        "hash",
        "<=>"
    };

    public static final Set<String> FRAME_AWARE_METHODS = Collections.synchronizedSet(new HashSet<String>());
    public static final Set<String> SCOPE_AWARE_METHODS = Collections.synchronizedSet(new HashSet<String>());

    public static final Map<String, Set<FrameField>> METHOD_FRAME_READS = new ConcurrentHashMap<>();
    public static final Map<String, Set<FrameField>> METHOD_FRAME_WRITES = new ConcurrentHashMap<>();

    public static CallSite getCallSite(String name) {
        // fast and safe respond_to? call site logic
        if (name.equals("respond_to?")) return new RespondToCallSite();

        // only use fast ops if we're not tracing
        if (RubyInstanceConfig.FASTOPS_COMPILE_ENABLED &&
                !(RubyInstanceConfig.FULL_TRACE_ENABLED)) return getFastFixnumOpsCallSite(name);

        return new NormalCachingCallSite(name);
    }

    private static final Map<String, String> FIXNUM_OPS;

    static {
        String[][] fastFixnumOps = {
                {"+", "op_plus"},
                {"-", "op_minus"},
                {"*", "op_mul"},
                {"==", "op_equal"},
                {"<", "op_lt"},
                {"<=", "op_le"},
                {">", "op_gt"},
                {">=", "op_ge"},
                {"<=>", "op_cmp"},
                {"&", "op_and"},
                {"|", "op_or"},
                {"^", "op_xor"},
                {">>", "op_rshift"},
                {"<<", "op_lshift"}
        };
        FIXNUM_OPS = new HashMap<>(fastFixnumOps.length + 1, 1);
        for (String[] fastOp : fastFixnumOps) FIXNUM_OPS.put(fastOp[0], fastOp[1]);
    }

    private static final Map<String, String> FLOAT_OPS;

    static {
        final String[][] fastFloatOps = {
                {"+", "op_plus"},
                {"-", "op_minus"},
                {"*", "op_mul"},
                {"==", "op_equal"},
                {"<", "op_lt"},
                {"<=", "op_le"},
                {">", "op_gt"},
                {">=", "op_ge"},
                {"<=>", "op_cmp"}
        };
        FLOAT_OPS = new HashMap<>(fastFloatOps.length + 1, 1);
        for (String[] fastOp : fastFloatOps) FLOAT_OPS.put(fastOp[0], fastOp[1]);
    }

    public static boolean hasFastFixnumOps(String name) {
        return FIXNUM_OPS.containsKey(name);
    }

    public static String getFastFixnumOpsMethod(String name) {
        return FIXNUM_OPS.get(name);
    }

    public static CallSite getFastFixnumOpsCallSite(String name) {
        switch (name) {
            case "+" : return new PlusCallSite();
            case "-" : return new MinusCallSite();
            case "*" : return new MulCallSite();
            case "<" : return new LtCallSite();
            case "<=" : return new LeCallSite();
            case ">" : return new GtCallSite();
            case ">=" : return new GeCallSite();
            case "==" : return new EqCallSite();
            case "<=>" : return new CmpCallSite();
            case "&" : return new BitAndCallSite();
            case "|" : return new BitOrCallSite();
            case "^" : return new XorCallSite();
            case ">>" : return new ShiftRightCallSite();
            case "<<" : return new ShiftLeftCallSite();
        }
        return new NormalCachingCallSite(name);
    }

    public static boolean hasFastFloatOps(String name) {
        return FLOAT_OPS.containsKey(name);
    }

    public static String getFastFloatOpsMethod(String name) {
        return FLOAT_OPS.get(name);
    }

    public static CallSite getFastFloatOpsCallSite(String name) {
        if (name.equals("+")) {
            return new PlusCallSite();
        } else if (name.equals("-")) {
            return new MinusCallSite();
        } else if (name.equals("*")) {
            return new MulCallSite();
        } else if (name.equals("<")) {
            return new LtCallSite();
        } else if (name.equals("<=")) {
            return new LeCallSite();
        } else if (name.equals(">")) {
            return new GtCallSite();
        } else if (name.equals(">=")) {
            return new GeCallSite();
        } else if (name.equals("==")) {
            return new EqCallSite();
        } else if (name.equals("<=>")) {
            return new CmpCallSite();
        }

        return new NormalCachingCallSite(name);
    }

    public static CallSite getFunctionalCallSite(String name) {
        return new FunctionalCachingCallSite(name);
    }

    public static CallSite getVariableCallSite(String name) {
        return new VariableCachingCallSite(name);
    }

    public static CallSite getSuperCallSite() {
        return new SuperCallSite();
    }

    public static void addMethodReadFieldsPacked(int readBits, String methodsPacked) {
        processFrameFields(readBits, methodsPacked, "read", METHOD_FRAME_READS);
    }

    public static void addMethodWriteFieldsPacked(int writeBits, String methodsPacked) {
        processFrameFields(writeBits, methodsPacked, "write", METHOD_FRAME_WRITES);
    }

    private static void processFrameFields(int bits, String methodNames, String usage, Map<String, Set<FrameField>> methodFrameAccesses) {
        Set<FrameField> writes = FrameField.unpack(bits);

        boolean needsFrame = FrameField.needsFrame(bits);
        boolean needsScope = FrameField.needsScope(bits);

        if (DEBUG) LOG.debug("Adding method fields for {}: {} for {}", usage, writes, methodNames);

        if (writes.size() > 0) {
            String[] names = Helpers.SEMICOLON_PATTERN.split(methodNames);
            List<String> namesList = Arrays.asList(names);

            addAwareness(needsFrame, needsScope, namesList);

            addFieldAccesses(methodFrameAccesses, names, writes);
        }
    }

    private static void addFieldAccesses(Map<String, Set<FrameField>> methodFrameWrites, String[] names, Set<FrameField> writes) {
        for (String name : names) {
            methodFrameWrites.compute(
                    name,
                    (key, cur) -> cur == null ? writes : concat(cur.stream(), writes.stream()).collect(toSet()));
        }
    }

    private static void addAwareness(boolean needsFrame, boolean needsScope, List<String> namesList) {
        if (needsFrame) FRAME_AWARE_METHODS.addAll(namesList);
        if (needsScope) SCOPE_AWARE_METHODS.addAll(namesList);
    }

    public static void addMethodReadFields(String name, FrameField[] reads) {
        addMethodReadFieldsPacked(FrameField.pack(reads), name);
    }

    public static void addMethodWriteFields(String name, FrameField[] write) {
        addMethodWriteFieldsPacked(FrameField.pack(write), name);
    }

    @Deprecated
    public static void addFrameAwareMethods(String... methods) {
        if (DEBUG) LOG.debug("Adding frame-aware method names: {}", Arrays.toString(methods));
        FRAME_AWARE_METHODS.addAll(Arrays.asList(methods));
    }

    @Deprecated
    public static void addScopeAwareMethods(String... methods) {
        if (DEBUG) LOG.debug("Adding scope-aware method names: {}", Arrays.toString(methods));
        SCOPE_AWARE_METHODS.addAll(Arrays.asList(methods));
    }
}
