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

import static com.android.SdkConstants.CLASS_INTENT;
import static org.jetbrains.uast.UastBinaryExpressionWithTypeKind.TYPE_CAST;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.UastLintUtils;
import com.android.tools.lint.detector.api.ConstantEvaluator;
import com.android.tools.lint.detector.api.JavaContext;

import org.jetbrains.uast.UAnnotation;
import org.jetbrains.uast.UBinaryExpressionWithType;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UParenthesizedExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastCallKind;
import org.jetbrains.uast.UastModifier;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.UastVariableKind;
import org.jetbrains.uast.expressions.UReferenceExpression;

import java.util.List;

/**
 * Utility for locating permissions required by an intent or content resolver
 */
public class PermissionFinder {
    /**
     * Operation that has a permission requirement -- such as a method call,
     * a content resolver read or write operation, an intent, etc.
     */
    public enum Operation {
        CALL, ACTION, READ, WRITE;

        /** Prefix to use when describing a name with a permission requirement */
        public String prefix() {
            switch (this) {
                case ACTION:
                    return "by intent";
                case READ:
                    return "to read";
                case WRITE:
                    return "to write";
                case CALL:
                default:
                    return "by";
            }
        }
    }

    /** A permission requirement given a name and operation */
    public static class Result {
        @NonNull public final PermissionRequirement requirement;
        @NonNull public final String name;
        @NonNull public final Operation operation;

        public Result(
                @NonNull Operation operation,
                @NonNull PermissionRequirement requirement,
                @NonNull String name) {
            this.operation = operation;
            this.requirement = requirement;
            this.name = name;
        }
    }

    /**
     * Searches for a permission requirement for the given parameter in the given call
     *
     * @param operation the operation to look up
     * @param context   the context to use for lookup
     * @param parameter the parameter which contains the value which implies the permission
     * @return the result with the permission requirement, or null if nothing is found
     */
    @Nullable
    public static Result findRequiredPermissions(
            @NonNull Operation operation,
            @NonNull JavaContext context,
            @NonNull UElement parameter) {

        // To find the permission required by an intent, we proceed in 3 steps:
        // (1) Locate the parameter in the start call that corresponds to
        //     the Intent
        //
        // (2) Find the place where the intent is initialized, and figure
        //     out the action name being passed to it.
        //
        // (3) Find the place where the action is defined, and look for permission
        //     annotations on that action declaration!

        return new PermissionFinder(context, operation).search(parameter);
    }

    private PermissionFinder(@NonNull JavaContext context, @NonNull Operation operation) {
        mContext = context;
        mOperation = operation;
    }

    @NonNull private final JavaContext mContext;
    @NonNull private final Operation mOperation;

    @Nullable
    public Result search(@NonNull UElement node) {
        if (node instanceof ULiteralExpression && ((ULiteralExpression) node).isNull()) {
            return null;
        } else if (node instanceof UIfExpression && !((UIfExpression) node).isStatement()) {
            UIfExpression expression = (UIfExpression) node;
            if (expression.getThenBranch() != null) {
                Result result = search(expression.getThenBranch());
                if (result != null) {
                    return result;
                }
            }
            if (expression.getElseBranch() != null) {
                Result result = search(expression.getElseBranch());
                if (result != null) {
                    return result;
                }
            }
        } else if (node instanceof UBinaryExpressionWithType
                && ((UBinaryExpressionWithType) node).getOperationKind() == TYPE_CAST) {
            UBinaryExpressionWithType cast = (UBinaryExpressionWithType) node;
            UExpression operand = cast.getOperand();
            return search(operand);
        } else if (node instanceof UParenthesizedExpression) {
            UParenthesizedExpression parens = (UParenthesizedExpression) node;
            UExpression expression = parens.getExpression();
            return search(expression);
        } else if (node instanceof UCallExpression && mOperation == Operation.ACTION) {
            // Identifies "new Intent(argument)" calls and, if found, continues
            // resolving the argument instead looking for the action definition
            UCallExpression call = (UCallExpression) node;
            if (call.getKind() == UastCallKind.CONSTRUCTOR_CALL) {
                UType type = call.resolveType(mContext);
                if (type != null && type.matchesFqName(CLASS_INTENT)) {
                    List<UExpression> expressions = call.getValueArguments();
                    if (!expressions.isEmpty()) {
                        UExpression action = expressions.get(0);
                        if (action != null) {
                            return search(action);
                        }
                    }
                }
            }
            return null;
        } else if (node instanceof UReferenceExpression) {
            UDeclaration resolved = ((UReferenceExpression) node).resolve(mContext);
            if (resolved instanceof UVariable) {
                UVariable variable = (UVariable) resolved;
                UExpression lastAssignment =
                        UastLintUtils.findLastAssignment(variable, node, mContext);

                if (lastAssignment != null ) {
                    search(lastAssignment);
                }
            }
            //TODO
        }

        return null;
    }

    @NonNull
    private Result getPermissionRequirement(
            @NonNull UVariable field,
            @NonNull UAnnotation annotation) {
        PermissionRequirement requirement = PermissionRequirement.create(mContext, annotation);
        UClass containingClass = UastUtils.getContainingClass(field);
        String name = containingClass != null
                ? containingClass.getName() + "." + field.getName()
                : field.getName();
        return new Result(mOperation, requirement, name);
    }
}
