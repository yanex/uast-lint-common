/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.checks;


import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.UastLintUtils;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.uast.UBinaryExpressionWithType;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UQualifiedExpression;
import org.jetbrains.uast.USimpleReferenceExpression;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detector for finding inefficiencies and errors in logging calls.
 */
public class LogDetector extends Detector implements Detector.UastScanner {
    private static final Implementation IMPLEMENTATION = new Implementation(
          LogDetector.class, Scope.JAVA_FILE_SCOPE);


    /** Log call missing surrounding if */
    public static final Issue CONDITIONAL = Issue.create(
            "LogConditional", //$NON-NLS-1$
            "Unconditional Logging Calls",
            "The BuildConfig class (available in Tools 17) provides a constant, \"DEBUG\", " +
            "which indicates whether the code is being built in release mode or in debug " +
            "mode. In release mode, you typically want to strip out all the logging calls. " +
            "Since the compiler will automatically remove all code which is inside a " +
            "\"if (false)\" check, surrounding your logging calls with a check for " +
            "BuildConfig.DEBUG is a good idea.\n" +
            "\n" +
            "If you *really* intend for the logging to be present in release mode, you can " +
            "suppress this warning with a @SuppressLint annotation for the intentional " +
            "logging calls.",

            Category.PERFORMANCE,
            5,
            Severity.WARNING,
            IMPLEMENTATION).setEnabledByDefault(false);

    /** Mismatched tags between isLogging and log calls within it */
    public static final Issue WRONG_TAG = Issue.create(
            "LogTagMismatch", //$NON-NLS-1$
            "Mismatched Log Tags",
            "When guarding a `Log.v(tag, ...)` call with `Log.isLoggable(tag)`, the " +
            "tag passed to both calls should be the same. Similarly, the level passed " +
            "in to `Log.isLoggable` should typically match the type of `Log` call, e.g. " +
            "if checking level `Log.DEBUG`, the corresponding `Log` call should be `Log.d`, " +
            "not `Log.i`.",

            Category.CORRECTNESS,
            5,
            Severity.ERROR,
            IMPLEMENTATION);

    /** Log tag is too long */
    public static final Issue LONG_TAG = Issue.create(
            "LongLogTag", //$NON-NLS-1$
            "Too Long Log Tags",
            "Log tags are only allowed to be at most 23 tag characters long.",

            Category.CORRECTNESS,
            5,
            Severity.ERROR,
            IMPLEMENTATION);

    @SuppressWarnings("SpellCheckingInspection")
    private static final String IS_LOGGABLE = "isLoggable";       //$NON-NLS-1$
    public static final String LOG_CLS = "android.util.Log";     //$NON-NLS-1$
    private static final String PRINTLN = "println";              //$NON-NLS-1$

    private static final Map<String, String> TAG_PAIRS;

    static {
        Map<String, String> pairs = new HashMap<String, String>();
        pairs.put("d", "DEBUG");
        pairs.put("e", "ERROR");
        pairs.put("i", "INFO");
        pairs.put("v", "VERBOSE");
        pairs.put("w", "WARN");
        TAG_PAIRS = Collections.unmodifiableMap(pairs);
    }

    // ---- Implements Detector.UastScanner ----

    @Override
    public List<String> getApplicableFunctionNames() {
        return Arrays.asList(
                "d",           //$NON-NLS-1$
                "e",           //$NON-NLS-1$
                "i",           //$NON-NLS-1$
                "v",           //$NON-NLS-1$
                "w",           //$NON-NLS-1$
                PRINTLN,
                IS_LOGGABLE);
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context,
            @Nullable UastVisitor visitor, @NonNull UCallExpression call,
            @NonNull UFunction function) {
        if (!UastLintUtils.matchesContainingClassFqName(function, LOG_CLS)) {
            return;
        }

        String name = function.getName();
        boolean withinConditional = IS_LOGGABLE.equals(name) ||
                checkWithinConditional(context, call.getParent(), call);

        // See if it's surrounded by an if statement (and it's one of the non-error, spammy
        // log methods (info, verbose, etc))
        if (("i".equals(name) || "d".equals(name) || "v".equals(name) || PRINTLN.equals(name))
                && !withinConditional
                && performsWork(context, call)
                && context.isEnabled(CONDITIONAL)) {
            String message = String.format("The log call Log.%1$s(...) should be " +
                            "conditional: surround with `if (Log.isLoggable(...))` or " +
                            "`if (BuildConfig.DEBUG) { ... }`",
                    function.getName());
            context.report(CONDITIONAL, call, context.getLocation(call), message);
        }

        // Check tag length
        if (context.isEnabled(LONG_TAG)) {
            int tagArgumentIndex = PRINTLN.equals(name) ? 1 : 0;
            List<UVariable> parameterList = function.getValueParameters();
            List<UExpression> argumentList = call.getValueArguments();
            if (function.getValueParameters().get(tagArgumentIndex).getType().isString()
                    && parameterList.size() == argumentList.size()) {
                UExpression argument = argumentList.get(tagArgumentIndex);
                String tag = argument.evaluateString();
                if (tag != null && tag.length() > 23) {
                    String message = String.format(
                            "The logging tag can be at most 23 characters, was %1$d (%2$s)",
                            tag.length(), tag);
                    context.report(LONG_TAG, call, context.getLocation(call), message);
                }
            }
        }
    }

    /** Returns true if the given logging call performs "work" to compute the message */
    private static boolean performsWork(
            @NonNull JavaContext context,
            @NonNull UCallExpression node) {
        String referenceName = node.getFunctionName();
        if (referenceName == null) {
            return false;
        }
        int messageArgumentIndex = PRINTLN.equals(referenceName) ? 2 : 1;
        List<UExpression> arguments = node.getValueArguments();
        if (arguments.size() > messageArgumentIndex) {
            UExpression argument = arguments.get(messageArgumentIndex);
            if (argument == null) {
                return false;
            }
            if (argument instanceof ULiteralExpression) {
                return false;
            }
            if (argument instanceof UBinaryExpressionWithType) {
                String string = argument.evaluateString();
                //noinspection VariableNotUsedInsideIf
                if (string != null) { // does it resolve to a constant?
                    return false;
                }
            } else if (argument instanceof USimpleReferenceExpression) {
                // Just a simple local variable/field reference
                return false;
            } else if (argument instanceof UQualifiedExpression) {
                String string = argument.evaluateString();
                //noinspection VariableNotUsedInsideIf
                if (string != null) {
                    return false;
                }
                UElement resolved = ((UQualifiedExpression) argument).resolve(context);
                if (resolved instanceof UVariable) {
                    // Just a reference to a property/field, parameter or variable
                    return false;
                }
            }

            // Method invocations etc
            return true;
        }

        return false;
    }

    private static boolean checkWithinConditional(
            @NonNull JavaContext context,
            @Nullable UElement curr,
            @NonNull UCallExpression logCall) {
        while (curr != null) {
            if (curr instanceof UIfExpression) {
                UIfExpression ifNode = (UIfExpression) curr;
                if (ifNode.getCondition() instanceof UCallExpression) {
                    UCallExpression call = (UCallExpression) ifNode.getCondition();
                    if (call.matchesFunctionName(IS_LOGGABLE)) {
                        checkTagConsistent(context, logCall, call);
                    }
                }

                return true;
            } else if (curr instanceof UCallExpression
                    || curr instanceof UFunction
                    || curr instanceof UClass) { // static block
                break;
            }
            curr = curr.getParent();
        }
        return false;
    }

    /** Checks that the tag passed to Log.s and Log.isLoggable match */
    private static void checkTagConsistent(JavaContext context, UCallExpression logCall,
            UCallExpression isLoggableCall) {
        List<UExpression> isLoggableArguments = isLoggableCall.getValueArguments();
        List<UExpression> logArguments = logCall.getValueArguments();
        if (isLoggableArguments.isEmpty() || logArguments.isEmpty()) {
            return;
        }
        UExpression isLoggableTag = isLoggableArguments.get(0);
        UExpression logTag = logArguments.get(0);

        String logCallName = logCall.getFunctionName();
        if (logCallName == null) {
            return;
        }
        boolean isPrintln = PRINTLN.equals(logCallName);
        if (isPrintln && logArguments.size() > 1) {
            logTag = logArguments.get(1);
        }

        if (logTag != null) {
            if (!UastLintUtils.areIdentifiersEqual(isLoggableTag, logTag)) {
                UDeclaration resolved1 = UastUtils.resolveIfCan(isLoggableTag, context);
                UDeclaration resolved2 = UastUtils.resolveIfCan(logTag, context);
                if ((resolved1 == null || resolved2 == null || !resolved1.equals(resolved2))
                        && context.isEnabled(WRONG_TAG)) {
                    Location location = context.getLocation(logTag);
                    Location alternate = context.getLocation(isLoggableTag);
                    alternate.setMessage("Conflicting tag");
                    location.setSecondary(alternate);
                    String isLoggableDescription = resolved1 != null
                            ? resolved1.getName()
                            : isLoggableTag.renderString();
                    String logCallDescription = resolved2 != null
                            ? resolved2.getName()
                            : logTag.renderString();
                    String message = String.format(
                            "Mismatched tags: the `%1$s()` and `isLoggable()` calls typically " +
                                    "should pass the same tag: `%2$s` versus `%3$s`",
                            logCallName,
                            isLoggableDescription,
                            logCallDescription);
                    context.report(WRONG_TAG, isLoggableCall, location, message);
                }
            }
        }

        // Check log level versus the actual log call type (e.g. flag
        //    if (Log.isLoggable(TAG, Log.DEBUG) Log.info(TAG, "something")

        if (logCallName.length() != 1 || isLoggableArguments.size() < 2) { // e.g. println
            return;
        }
        UExpression isLoggableLevel = isLoggableArguments.get(1);
        if (isLoggableLevel == null) {
            return;
        }

        UDeclaration resolved = UastUtils.resolveIfCan(isLoggableLevel, context);
        if (resolved == null) {
            return;
        }

        if (resolved instanceof UVariable) {
            if (UastUtils.getContainingClassOrEmpty(resolved).matchesFqName("android.util.Log")) {
                if (!resolved.matchesName(TAG_PAIRS.get(logCallName))) {
                    String expectedCall = resolved.getName().substring(0, 1)
                            .toLowerCase(Locale.getDefault());

                    String message = String.format(
                            "Mismatched logging levels: when checking `isLoggable` level `%1$s`, the " +
                                    "corresponding log call should be `Log.%2$s`, not `Log.%3$s`",
                            resolved.getName(), expectedCall, logCallName);
                    Location location = context.getLocation(logCall.getFunctionNameElement());
                    Location alternate = context.getLocation(isLoggableLevel);
                    alternate.setMessage("Conflicting tag");
                    location.setSecondary(alternate);
                    context.report(WRONG_TAG, isLoggableCall, location, message);
                }
            }
        }
    }
}
