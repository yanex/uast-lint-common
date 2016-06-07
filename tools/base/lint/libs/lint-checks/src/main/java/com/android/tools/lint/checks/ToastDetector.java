/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static com.android.tools.lint.client.api.UastLintUtils.isChildOfExpression;
import static com.android.tools.lint.client.api.UastLintUtils.resolveReferenceInitializer;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.UastLintUtils;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Detector.UastScanner;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UQualifiedExpression;
import org.jetbrains.uast.UReturnExpression;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Collections;
import java.util.List;

/** Detector looking for Toast.makeText() without a corresponding show() call */
public class ToastDetector extends Detector implements UastScanner {
    /** The main issue discovered by this detector */
    public static final Issue ISSUE = Issue.create(
            "ShowToast", //$NON-NLS-1$
            "Toast created but not shown",

            "`Toast.makeText()` creates a `Toast` but does *not* show it. You must call " +
            "`show()` on the resulting object to actually make the `Toast` appear.",

            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            new Implementation(
                    ToastDetector.class,
                    Scope.JAVA_FILE_SCOPE));


    /** Constructs a new {@link ToastDetector} check */
    public ToastDetector() {
    }

    // ---- Implements JavaScanner ----

    @Override
    public List<String> getApplicableFunctionNames() {
        return Collections.singletonList("makeText"); //$NON-NLS-1$
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context, @Nullable UastVisitor visitor,
            @NonNull UCallExpression call, @NonNull UFunction function) {
        if (!UastUtils.getContainingClassOrEmpty(function).matchesFqName("android.widget.Toast")) {
            return;
        }

        // Make sure you pass the right kind of duration: it's not a delay, it's
        //  LENGTH_SHORT or LENGTH_LONG
        // (see http://code.google.com/p/android/issues/detail?id=3655)
        List<UExpression> args = call.getValueArguments();
        if (args.size() == 3) {
            UExpression duration = args.get(2);
            if (duration instanceof ULiteralExpression) {
                context.report(ISSUE, duration, context.getLocation(duration),
                        "Expected duration `Toast.LENGTH_SHORT` or `Toast.LENGTH_LONG`, a custom " +
                                "duration value is not supported");
            }
        }

        UFunction surroundingFunction = UastUtils.getParentOfType(call, UFunction.class);
        if (surroundingFunction == null) {
            return;
        }

        UExpression qualifiedCallExpression = UastLintUtils.getQualifiedCallExpression(call);
        ShowFinder finder = new ShowFinder(qualifiedCallExpression, context);
        surroundingFunction.accept(finder);
        if (!finder.isShowCalled()) {
            context.report(ISSUE, call, context.getLocation(call.getFunctionNameElement()),
                    "Toast created but not shown: did you forget to call `show()` ?");
        }
    }

    private static class ShowFinder extends AbstractUastVisitor {
        /** The target makeText call */
        private final UExpression mTarget;

        private final UastContext mContext;

        /** Whether we've found the show method */
        private boolean mFound;

        private ShowFinder(UExpression target, UastContext context) {
            mTarget = target;
            mContext = context;
        }

        @Override
        public boolean visitQualifiedExpression(@NotNull UQualifiedExpression node) {
            if (node.getSelector() instanceof UCallExpression) {
                UExpression receiver = node.getReceiver();
                if (receiver.equals(mTarget) ||
                        resolveReferenceInitializer(receiver, mContext).equals(mTarget)) {
                    UCallExpression call = ((UCallExpression) node.getSelector());
                    if (call.matchesFunctionNameWithContaining(
                            "android.widget.Toast", "show", mContext)) {
                        mFound = true;
                    }
                }
            }

            return super.visitQualifiedExpression(node);
        }

        @Override
        public boolean visitReturnExpression(@NonNull UReturnExpression node) {
            if (isChildOfExpression(mTarget, node)) {
                // If you just do "return Toast.makeText(...) don't warn
                mFound = true;
            }

            return super.visitReturnExpression(node);
        }

        boolean isShowCalled() {
            return mFound;
        }
    }
}
