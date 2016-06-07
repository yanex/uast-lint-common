/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.SdkConstants.CLASS_VIEW;
import static com.android.SdkConstants.SUPPORT_ANNOTATIONS_PREFIX;
import static com.android.tools.lint.checks.SupportAnnotationDetector.filterRelevantAnnotations;
import static com.android.tools.lint.detector.api.LintUtils.skipParentheses;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.JavaEvaluator;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.uast.UAnnotation;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.USuperExpression;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.expressions.UReferenceExpression;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Collections;
import java.util.List;

/**
 * Makes sure that methods call super when overriding methods.
 */
public class CallSuperDetector extends Detector implements Detector.UastScanner {
    private static final String CALL_SUPER_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "CallSuper"; //$NON-NLS-1$
    private static final String ON_DETACHED_FROM_WINDOW = "onDetachedFromWindow";   //$NON-NLS-1$
    private static final String ON_VISIBILITY_CHANGED = "onVisibilityChanged";      //$NON-NLS-1$

    private static final Implementation IMPLEMENTATION = new Implementation(
            CallSuperDetector.class,
            Scope.JAVA_FILE_SCOPE);

    /** Missing call to super */
    public static final Issue ISSUE = Issue.create(
            "MissingSuperCall", //$NON-NLS-1$
            "Missing Super Call",

            "Some methods, such as `View#onDetachedFromWindow`, require that you also " +
            "call the super implementation as part of your method.",

            Category.CORRECTNESS,
            9,
            Severity.ERROR,
            IMPLEMENTATION);

    /** Constructs a new {@link CallSuperDetector} check */
    public CallSuperDetector() {
    }

    // ---- Implements UastScanner ----

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return Collections.<Class<? extends UElement>>singletonList(UFunction.class);
    }

    @Nullable
    @Override
    public UastVisitor createUastVisitor(@NonNull final JavaContext context) {
        return new AbstractUastVisitor() {
            @Override
            public boolean visitFunction(UFunction node) {
                checkCallSuper(context, node);
                return super.visitFunction(node);
            }
        };
    }

    private static void checkCallSuper(@NonNull JavaContext context,
            @NonNull UFunction method) {

        UFunction superFunction = getRequiredSuperFunction(context, method);
        if (superFunction != null) {
            if (!SuperCallVisitor.callsSuper(method, superFunction, context)) {
                String functionName = method.getName();
                String message = "Overriding method should call `super."
                        + functionName + "`";
                Location location = context.getLocation(method.getNameElement());
                context.report(ISSUE, method, location, message);
            }
        }
    }

    /**
     * Checks whether the given method overrides a method which requires the super method
     * to be invoked, and if so, returns it (otherwise returns null)
     */
    @Nullable
    private static UFunction getRequiredSuperFunction(@NonNull JavaContext context,
            @NonNull UFunction method) {

        List<UFunction> superFunctions = method.getOverriddenDeclarations(context);
        if (superFunctions.isEmpty()) {
            return null;
        }

        String name = method.getName();
        if (ON_DETACHED_FROM_WINDOW.equals(name)) {
            // No longer annotated on the framework method since it's
            // now handled via onDetachedFromWindowInternal, but overriding
            // is still dangerous if supporting older versions so flag
            // this for now (should make annotation carry metadata like
            // compileSdkVersion >= N).
            if (!UastUtils.getContainingClassOrEmpty(method).isSubclassOf(CLASS_VIEW, false)) {
                return null;
            }
            return superFunctions.get(0);
        } else if (ON_VISIBILITY_CHANGED.equals(name)) {
            // From Android Wear API; doesn't yet have an annotation
            // but we want to enforce this right away until the AAR
            // is updated to supply it once @CallSuper is available in
            // the support library
            if (!JavaEvaluator.isMemberInSubClassOf(method,
                    "android.support.wearable.watchface.WatchFaceService.Engine", false)) {
                return null;
            }
            return superFunctions.get(0);
        }

        // Look up annotations metadata
        for (UFunction superFunction : superFunctions) {
            List<UAnnotation> annotations = filterRelevantAnnotations(
                    context.getAnnotationsWithExternal(superFunction), context);
            for (UAnnotation annotation : annotations) {
                String signature = annotation.getFqName();
                if (CALL_SUPER_ANNOTATION.equals(signature)) {
                    return superFunction;
                } else if (signature != null && signature.endsWith(".OverrideMustInvoke")) {
                    // Handle findbugs annotation on the fly too
                    return superFunction;
                }
            }
        }

        return null;
    }

    /** Visits a method and determines whether the method calls its super method */
    private static class SuperCallVisitor extends AbstractUastVisitor {
        private final UastContext mContext;
        private final UFunction mMethod;
        private boolean mCallsSuper;

        public static boolean callsSuper(@NonNull UFunction method,
                @NonNull UFunction superMethod,
                @NonNull  UastContext context) {
            SuperCallVisitor visitor = new SuperCallVisitor(superMethod, context);
            method.accept(visitor);
            return visitor.mCallsSuper;
        }

        private SuperCallVisitor(@NonNull UFunction method, UastContext context) {
            mMethod = method;
            mContext = context;
        }

        @Override
        public boolean visitSuperExpression(USuperExpression node) {
            UElement parent = skipParentheses(node.getParent());
            if (parent instanceof UReferenceExpression) {
                UDeclaration resolved = ((UReferenceExpression) parent).resolve(mContext);
                if (mMethod.equals(resolved)) {
                    mCallsSuper = true;
                }
            }

            return super.visitSuperExpression(node);
        }
    }
}
