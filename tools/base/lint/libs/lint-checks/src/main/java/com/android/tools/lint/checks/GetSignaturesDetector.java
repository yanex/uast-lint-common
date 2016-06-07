/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static org.jetbrains.uast.util.UTypeConstraint.PRIMITIVE_INT;
import static org.jetbrains.uast.util.UTypeConstraint.STRING;
import static org.jetbrains.uast.util.UastSignatureChecker.matchesSignature;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.JavaParser;
import com.android.tools.lint.client.api.UastLintUtils;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.ConstantEvaluator;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.util.UTypeConstraint;
import org.jetbrains.uast.util.UastSignatureChecker;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Collections;
import java.util.List;

public class GetSignaturesDetector extends Detector implements Detector.UastScanner {
    public static final Issue ISSUE = Issue.create(
            "PackageManagerGetSignatures", //$NON-NLS-1$
            "Potential Multiple Certificate Exploit",
            "Improper validation of app signatures could lead to issues where a malicious app " +
            "submits itself to the Play Store with both its real certificate and a fake " +
            "certificate and gains access to functionality or information it shouldn't " +
            "have due to another application only checking for the fake certificate and " +
            "ignoring the rest. Please make sure to validate all signatures returned " +
            "by this method.",
            Category.SECURITY,
            8,
            Severity.INFORMATIONAL,
            new Implementation(
                    GetSignaturesDetector.class,
                    Scope.JAVA_FILE_SCOPE))
            .addMoreInfo("https://bluebox.com/technical/android-fake-id-vulnerability/");

    private static final String PACKAGE_MANAGER_CLASS = "android.content.pm.PackageManager"; //$NON-NLS-1$
    private static final String GET_PACKAGE_INFO = "getPackageInfo"; //$NON-NLS-1$
    private static final int GET_SIGNATURES_FLAG = 0x00000040; //$NON-NLS-1$

    // ---- Implements UastScanner ----

    @Nullable
    @Override
    public List<String> getApplicableFunctionNames() {
        return Collections.singletonList(GET_PACKAGE_INFO);
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context,
            @Nullable UastVisitor visitor, @NonNull UCallExpression call,
            @NonNull UFunction function) {
        if (!function.matchesContaining(PACKAGE_MANAGER_CLASS, true)
                || !matchesSignature(function, STRING, PRIMITIVE_INT)
                || call.getValueArgumentCount() != 2) {
            return;
        }

        List<UExpression> arguments = call.getValueArguments();
        UExpression second = arguments.get(1);
        Object number = ConstantEvaluator.evaluate(context, second);
        if (number instanceof Number) {
            int flagValue = ((Number)number).intValue();
            maybeReportIssue(flagValue, context, call, second);
        }
    }

    private static void maybeReportIssue(
            int flagValue, JavaContext context, UCallExpression node,
            UExpression last) {
        if ((flagValue & GET_SIGNATURES_FLAG) != 0) {
            context.report(ISSUE, node, context.getLocation(last),
                "Reading app signatures from getPackageInfo: The app signatures "
                    + "could be exploited if not validated properly; "
                    + "see issue explanation for details.");
        }
    }
}
