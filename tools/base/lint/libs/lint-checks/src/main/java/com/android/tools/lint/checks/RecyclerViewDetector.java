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


import static com.android.tools.lint.checks.CutPasteDetector.isReachableFrom;
import static org.jetbrains.uast.UastVariableKind.LOCAL_VARIABLE;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.USimpleReferenceExpression;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastBinaryOperator;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastModifier;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.expressions.UReferenceExpression;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Checks related to RecyclerView usage.
 */
public class RecyclerViewDetector extends Detector implements Detector.UastScanner {

    public static final Implementation IMPLEMENTATION = new Implementation(
            RecyclerViewDetector.class,
            Scope.JAVA_FILE_SCOPE);

    public static final Issue FIXED_POSITION = Issue.create(
            "RecyclerView", //$NON-NLS-1$
            "RecyclerView Problems",
            "`RecyclerView` will *not* call `onBindViewHolder` again when the position of " +
            "the item changes in the data set unless the item itself is " +
            "invalidated or the new position cannot be determined.\n" +
            "\n" +
            "For this reason, you should *only* use the position parameter " +
            "while acquiring the related data item inside this method, and " +
            "should *not* keep a copy of it.\n" +
            "\n" +
            "If you need the position of an item later on (e.g. in a click " +
            "listener), use `getAdapterPosition()` which will have the updated " +
            "adapter position.",
            Category.CORRECTNESS,
            8,
            Severity.ERROR,
            IMPLEMENTATION);

    public static final Issue DATA_BINDER = Issue.create(
            "PendingBindings", //$NON-NLS-1$
            "Missing Pending Bindings",
            "When using a `ViewDataBinding` in a `onBindViewHolder` method, you *must* " +
            "call `executePendingBindings()` before the method exits; otherwise " +
            "the data binding runtime will update the UI in the next animation frame " +
            "causing a delayed update and potential jumps if the item resizes.",
            Category.CORRECTNESS,
            8,
            Severity.ERROR,
            IMPLEMENTATION);

    private static final String VIEW_ADAPTER = "android.support.v7.widget.RecyclerView.Adapter"; //$NON-NLS-1$
    private static final String ON_BIND_VIEW_HOLDER = "onBindViewHolder"; //$NON-NLS-1$

    // ---- Implements UastScanner ----

    @Nullable
    @Override
    public List<String> applicableSuperClasses() {
        return Collections.singletonList(VIEW_ADAPTER);
    }

    @Override
    public void checkClass(@NonNull JavaContext context, @NonNull UClass declaration) {
        for (UFunction function : UastUtils.findFunctions(declaration, ON_BIND_VIEW_HOLDER)) {
            int size = function.getValueParameterCount();
            if (size == 2 || size == 3) {
                checkFunction(context, function, declaration);
            }
        }
    }

    private static void checkFunction(@NonNull JavaContext context,
            @NonNull UFunction declaration, @NonNull UClass cls) {
        List<UVariable> parameters = declaration.getValueParameters();
        UVariable viewHolder = parameters.get(0);
        UVariable parameter = parameters.get(1);

        ParameterEscapesVisitor visitor = new ParameterEscapesVisitor(context, cls, parameter);
        declaration.accept(visitor);
        if (visitor.variableEscapes()) {
            reportError(context, viewHolder, parameter);
        }

        // Look for pending data binder calls that aren't executed before the method finishes
        List<UCallExpression> dataBinderReferences = visitor.getDataBinders();
        checkDataBinders(context, declaration, dataBinderReferences);
    }

    private static void reportError(@NonNull JavaContext context, UVariable viewHolder,
            UVariable parameter) {
        String variablePrefix = viewHolder.getName();
        String message = String.format("Do not treat position as fixed; only use immediately "
                + "and call `%1$s.getAdapterPosition()` to look it up later",
                variablePrefix);
        context.report(FIXED_POSITION, parameter, context.getLocation(parameter),
                message);
    }

    private static void checkDataBinders(@NonNull JavaContext context,
            @NonNull UFunction declaration, List<UCallExpression> references) {
        if (references != null && !references.isEmpty()) {
            List<UCallExpression> targets = Lists.newArrayList();
            List<UCallExpression> sources = Lists.newArrayList();
            for (UCallExpression ref : references) {
                if (isExecutePendingBindingsCall(ref)) {
                    targets.add(ref);
                } else {
                    sources.add(ref);
                }
            }

            // Only operate on the last call in each block: ignore siblings with the same parent
            // That way if you have
            //     dataBinder.foo();
            //     dataBinder.bar();
            //     dataBinder.baz();
            // we only flag the *last* of these calls as needing an executePendingBindings
            // afterwards. We do this with a parent map such that we correctly pair
            // elements when they have nested references within (such as if blocks.)
            Map<UElement, UCallExpression> parentToChildren = Maps.newHashMap();
            for (UCallExpression reference : sources) {
                // Note: We're using a map, not a multimap, and iterating forwards:
                // this means that the *last* element will overwrite previous entries,
                // and we end up with the last reference for each parent which is what we
                // want
                UExpression expression = UastUtils.getParentOfType(reference, UExpression.class);
                if (expression != null) {
                    parentToChildren.put(expression.getParent(), reference);
                }
            }

            for (UCallExpression source : parentToChildren.values()) {
                UExpression sourceBinderReference = UastUtils.getQualifiedParentOrThis(source);
                UVariable sourceDataBinder = getDataBinderReference(sourceBinderReference, context);
                assert sourceDataBinder != null;

                boolean reachesTarget = false;
                for (UCallExpression target : targets) {
                    if (sourceDataBinder.equals(getDataBinderReference(
                            UastUtils.getQualifiedParentOrThis(target), context))
                            // TODO: Provide full control flow graph, or at least provide an
                            // isReachable method which can take multiple targets
                            && isReachableFrom(declaration, source, target)) {
                        reachesTarget = true;
                        break;
                    }
                }
                if (!reachesTarget) {
                    String message = String.format(
                            "You must call `%1$s.executePendingBindings()` "
                                + "before the `onBind` method exits, otherwise, the DataBinding "
                                + "library will update the UI in the next animation frame "
                                + "causing a delayed update & potential jumps if the item "
                                + "resizes.",
                            sourceBinderReference.originalString());
                    context.report(DATA_BINDER, source, context.getLocation(source), message);
                }
            }
        }
    }

    private static boolean isExecutePendingBindingsCall(UCallExpression call) {
        return call.matchesFunctionName("executePendingBindings");
    }

    @Nullable
    private static UVariable getDataBinderReference(
            @Nullable UElement element,
            @NonNull  UastContext context) {
        if (element instanceof UReferenceExpression) {
            UElement resolved = ((UReferenceExpression) element).resolve(context);
            if (resolved instanceof UVariable) {
                UVariable field = (UVariable) resolved;
                if (field.matchesName("dataBinder")) {
                    return field;
                }
            }
        }

        return null;
    }

    /**
     * Determines whether a given variable "escapes" either to a field or to a nested
     * runnable. (We deliberately ignore variables that escape via method calls.)
     */
    private static class ParameterEscapesVisitor extends AbstractUastVisitor {
        protected final JavaContext mContext;
        protected final List<UVariable> mVariables;
        private final UClass mBindClass;
        private boolean mEscapes;
        private boolean mFoundInnerClass;

        public ParameterEscapesVisitor(JavaContext context,
                @NonNull UClass bindClass,
                @NonNull UVariable variable) {
            mContext = context;
            mVariables = Lists.<UVariable>newArrayList(variable);
            mBindClass = bindClass;
        }

        public boolean variableEscapes() {
            return mEscapes;
        }

        @Override
        public boolean visitVariable(UVariable node) {
            if (node.getKind() == LOCAL_VARIABLE) {
                UExpression initializer = node.getInitializer();
                if (initializer instanceof UReferenceExpression) {
                    UElement resolved = ((UReferenceExpression) initializer).resolve(mContext);
                    //noinspection SuspiciousMethodCalls
                    if (resolved != null && mVariables.contains(resolved)) {
                        if (resolved instanceof UVariable) {
                            if (((UVariable) resolved).getKind() == LOCAL_VARIABLE) {
                                mVariables.add((UVariable) resolved);
                            } else {
                                mEscapes = true;
                            }
                        }
                    }
                }
            }

            return super.visitVariable(node);
        }

        @Override
        public boolean visitBinaryExpression(UBinaryExpression node) {
            if (node.getOperator() instanceof UastBinaryOperator.AssignOperator) {
                UExpression rhs = node.getRightOperand();
                boolean clearLhs = true;
                if (rhs instanceof UReferenceExpression) {
                    UElement resolved = ((UReferenceExpression)rhs).resolve(mContext);
                    //noinspection SuspiciousMethodCalls
                    if (resolved != null && mVariables.contains(resolved)) {
                        clearLhs = false;
                        UElement resolvedLhs = UastUtils.resolveIfCan(
                                node.getLeftOperand(), mContext);

                        if (resolvedLhs instanceof UVariable) {
                            UVariable variable = (UVariable) resolvedLhs;
                            if (variable.getKind() == LOCAL_VARIABLE) {
                                mVariables.add(variable);
                            } else {
                                mEscapes = true;
                            }
                        }
                    }
                }
                if (clearLhs) {
                    // If we reassign one of the variables, clear it out
                    UElement resolved = UastUtils.resolveIfCan(node.getLeftOperand(), mContext);
                    //noinspection SuspiciousMethodCalls
                    if (resolved != null && mVariables.contains(resolved)) {
                        //noinspection SuspiciousMethodCalls
                        mVariables.remove(resolved);
                    }
                }
            }

            return super.visitBinaryExpression(node);
        }

        @Override
        public boolean visitSimpleReferenceExpression(USimpleReferenceExpression node) {
            if (mFoundInnerClass) {
                // Check to see if this reference is inside the same class as the original
                // onBind (e.g. is this a reference from an inner class, or a reference
                // to a variable assigned from there)
                UDeclaration resolved = node.resolve(mContext);
                //noinspection SuspiciousMethodCalls
                if (resolved != null && mVariables.contains(resolved)) {
                    UClass outer = UastUtils.getParentOfType(node, UClass.class, true);
                    if (!mBindClass.equals(outer)) {
                        mEscapes = true;
                    }
                }
            }

            return super.visitSimpleReferenceExpression(node);
        }

        @Override
        public boolean visitClass(UClass node) {
            if (node.isAnonymous() || !node.hasModifier(UastModifier.STATIC)) {
                mFoundInnerClass = true;
            }

            return super.visitClass(node);
        }

        // Also look for data binder references

        private List<UCallExpression> mDataBinders = null;

        @Nullable
        public List<UCallExpression> getDataBinders() {
            return mDataBinders;
        }

        @Override
        public boolean visitCallExpression(UCallExpression node) {
            UExpression qualifier = UastUtils.getQualifiedParentOrThis(node);
            UVariable dataBinder = getDataBinderReference(qualifier, mContext);
            //noinspection VariableNotUsedInsideIf
            if (dataBinder != null) {
                if (mDataBinders == null) {
                    mDataBinders = Lists.newArrayList();
                }
                mDataBinders.add(node);
            }

            return super.visitCallExpression(node);
        }
    }
}
