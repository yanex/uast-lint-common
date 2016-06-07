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

import static com.android.tools.lint.client.api.JavaParser.TYPE_STRING;
import static org.jetbrains.uast.util.UTypeConstraint.STRING;
import static org.jetbrains.uast.util.UastSignatureChecker.matchesSignature;

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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.EmptyUExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UReturnExpression;
import org.jetbrains.uast.UThrowExpression;
import org.jetbrains.uast.UastLiteralUtils;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.util.UTypeConstraint;
import org.jetbrains.uast.util.UastSignatureChecker;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.Collections;
import java.util.List;

public class BadHostnameVerifierDetector extends Detector implements Detector.UastScanner {

    @SuppressWarnings("unchecked")
    private static final Implementation IMPLEMENTATION =
            new Implementation(BadHostnameVerifierDetector.class,
                    Scope.JAVA_FILE_SCOPE);

    public static final Issue ISSUE = Issue.create("BadHostnameVerifier",
            "Insecure HostnameVerifier",
            "This check looks for implementations of `HostnameVerifier` " +
            "whose `verify` method always returns true (thus trusting any hostname) " +
            "which could result in insecure network traffic caused by trusting arbitrary " +
            "hostnames in TLS/SSL certificates presented by peers.",
            Category.SECURITY,
            6,
            Severity.WARNING,
            IMPLEMENTATION);

    // ---- Implements UastScanner ----

    @Nullable
    @Override
    public List<String> applicableSuperClasses() {
        return Collections.singletonList("javax.net.ssl.HostnameVerifier");
    }

    @Override
    public void checkClass(@NonNull JavaContext context, @NonNull UClass declaration) {
        for (UFunction function : UastUtils.findFunctions(declaration, "verify")) {
            if (matchesSignature(function, STRING, UTypeConstraint.make("javax.net.ssl.SSLSession"))) {
                ComplexVisitor visitor = new ComplexVisitor(context);
                declaration.accept(visitor);
                if (visitor.isComplex()) {
                    return;
                }

                Location location = context.getLocation(function.getNameElement());
                String message = String.format("`%1$s` always returns `true`, which " +
                                "could cause insecure network traffic due to trusting "
                                + "TLS/SSL server certificates for wrong hostnames",
                        function.getName());
                context.report(ISSUE, location, message);
                break;
            }
        }

    }

    private static class ComplexVisitor extends AbstractUastVisitor {
        @SuppressWarnings("unused")
        private final JavaContext mContext;
        private boolean mComplex;

        public ComplexVisitor(JavaContext context) {
            mContext = context;
        }

        @Override
        public boolean visitThrowExpression(@NotNull UThrowExpression node) {
            mComplex = true;
            return super.visitThrowExpression(node);
        }

        @Override
        public boolean visitCallExpression(@NotNull UCallExpression node) {
            // TODO: Ignore certain known safe methods, e.g. Logging etc
            mComplex = true;
            return super.visitCallExpression(node);
        }

        @Override
        public boolean visitReturnExpression(@NotNull UReturnExpression node) {
            UExpression argument = node.getReturnExpression();
            if (argument != null && !(argument instanceof EmptyUExpression)) {
                // TODO: Only do this if certain that there isn't some intermediate
                // assignment, as exposed by the unit test
                //Object value = ConstantEvaluator.evaluate(mContext, argument);
                //if (Boolean.TRUE.equals(value)) {
                if (UastLiteralUtils.isTrueLiteral(argument)) {
                    mComplex = false;
                } else {
                    mComplex = true; // "return false" or some complicated logic
                }
            }
            return super.visitReturnExpression(node);
        }

        public boolean isComplex() {
            return mComplex;
        }
    }
}
