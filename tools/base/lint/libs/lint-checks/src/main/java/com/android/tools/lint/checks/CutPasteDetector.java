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

import static com.android.SdkConstants.RESOURCE_CLZ_ID;
import static com.android.tools.lint.detector.api.LintUtils.skipParentheses;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.google.common.collect.Maps;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiArrayAccessExpression;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiBreakStatement;
import com.intellij.psi.PsiContinueStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.uast.UArrayAccessExpression;
import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UContinueExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UQualifiedExpression;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.expressions.UReferenceExpression;
import org.jetbrains.uast.util.UastExpressionUtils;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Detector looking for cut & paste issues
 */
public class CutPasteDetector extends Detector implements Detector.UastScanner {
    /** The main issue discovered by this detector */
    public static final Issue ISSUE = Issue.create(
            "CutPasteId", //$NON-NLS-1$
            "Likely cut & paste mistakes",

            "This lint check looks for cases where you have cut & pasted calls to " +
            "`findViewById` but have forgotten to update the R.id field. It's possible " +
            "that your code is simply (redundantly) looking up the field repeatedly, " +
            "but lint cannot distinguish that from a case where you for example want to " +
            "initialize fields `prev` and `next` and you cut & pasted `findViewById(R.id.prev)` " +
            "and forgot to update the second initialization to `R.id.next`.",

            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            new Implementation(
                    CutPasteDetector.class,
                    Scope.JAVA_FILE_SCOPE));

    private UFunction mLastMethod;
    private Map<String, UCallExpression> mIds;
    private Map<String, String> mLhs;
    private Map<String, String> mCallOperands;

    /** Constructs a new {@link CutPasteDetector} check */
    public CutPasteDetector() {
    }

    // ---- Implements UastScanner ----


    @Nullable
    @Override
    public List<String> getApplicableFunctionNames() {
        return Collections.singletonList("findViewById"); //$NON-NLS-1$
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context,
            @Nullable UastVisitor visitor, @NonNull UCallExpression call,
            @NonNull UFunction function) {
        String lhs = getLhs(call);
        if (lhs == null) {
            return;
        }

        UFunction method = UastUtils.getParentOfType(call, UFunction.class, false);
        if (method == null) {
            return; // prevent doing the same work for multiple findViewById calls in same method
        } else if (method != mLastMethod) {
            mIds = Maps.newHashMap();
            mLhs = Maps.newHashMap();
            mCallOperands = Maps.newHashMap();
            mLastMethod = method;
        }

        UExpression receiver = UastUtils.getReceiver(call);
        String callOperand =  receiver != null ? receiver.renderString() : "";

        List<UExpression> arguments = call.getValueArguments();
        if (arguments.isEmpty()) {
            return;
        }
        UExpression first = arguments.get(0);
        if (first instanceof UReferenceExpression) {
            UReferenceExpression referenceExpression = (UReferenceExpression) first;
            String id = referenceExpression.getIdentifier();
            UExpression operand = referenceExpression instanceof UQualifiedExpression
                    ? ((UQualifiedExpression) referenceExpression).getReceiver()
                    : null;

            if (operand instanceof UReferenceExpression) {
                UReferenceExpression type = (UReferenceExpression) operand;
                if (RESOURCE_CLZ_ID.equals(type.getIdentifier())) {
                    if (mIds.containsKey(id)) {
                        if (lhs.equals(mLhs.get(id))) {
                            return;
                        }
                        if (!callOperand.equals(mCallOperands.get(id))) {
                            return;
                        }
                        UCallExpression earlierCall = mIds.get(id);
                        if (!isReachableFrom(method, earlierCall, call)) {
                            return;
                        }
                        Location location = context.getLocation(call);
                        Location secondary = context.getLocation(earlierCall);
                        secondary.setMessage("First usage here");
                        location.setSecondary(secondary);
                        context.report(ISSUE, call, location, String.format(
                                "The id `%1$s` has already been looked up in this method; possible "
                                        +
                                        "cut & paste error?", first.originalString()));
                    } else {
                        mIds.put(id, call);
                        mLhs.put(id, lhs);
                        mCallOperands.put(id, callOperand);
                    }
                }

            }
        }
    }

    @Nullable
    private static String getLhs(@NonNull UCallExpression call) {
        UElement parent = skipParentheses(call.getParent());
        if (parent != null && UastExpressionUtils.isTypeCast(parent)) {
            parent = parent.getParent();
        }

        if (parent == null) {
            return null;
        }

        if (parent instanceof UVariable) {
            return ((UVariable) parent).getName();
        } else if (parent instanceof UBinaryExpression) {
            UBinaryExpression be = (UBinaryExpression) parent;
            UExpression left = be.getLeftOperand();
            if (left instanceof UReferenceExpression) {
                return ((UReferenceExpression) left).getIdentifier();
            } else if (left instanceof UArrayAccessExpression) {
                UArrayAccessExpression aa = (UArrayAccessExpression) left;
                return aa.getReceiver().renderString();
            }
        } else if (UastExpressionUtils.isAssignment(parent)) {
            //noinspection ConstantConditions
            UExpression left = ((UBinaryExpression) parent).getLeftOperand();
            if (left instanceof UReferenceExpression) {
                return ((UReferenceExpression) left).getIdentifier();
            } else if (left instanceof UArrayAccessExpression) {
                UArrayAccessExpression aa = (UArrayAccessExpression) left;
                return aa.getReceiver().renderString();
            }
        }

        return null;
    }

    static boolean isReachableFrom(
            @NonNull PsiMethod method,
            @NonNull PsiElement from,
            @NonNull PsiElement to) {
        PsiElement prev = from;
        PsiElement curr = next(method, from, to, null);
        //noinspection ConstantConditions
        while (curr != null) {
            if (containsElement(method, curr, to)) {
                return true;
            }
            curr = next(method, curr, to, prev);
            prev = curr;
        }

        return false;
    }

    static boolean isReachableFrom(
            @NonNull UFunction method,
            @NonNull UElement from,
            @NonNull UElement to) {
        /*UElement prev = from;
        UElement curr = next(method, from, to, null);
        //noinspection ConstantConditions
        while (curr != null) {
            if (containsElement(method, curr, to)) {
                return true;
            }
            curr = next(method, curr, to, prev);
            prev = curr;
        }*/

        return false;
    }

    @Nullable
    static PsiElement next(
            @NonNull PsiMethod method,
            @NonNull PsiElement curr,
            @NonNull PsiElement target,
            @Nullable PsiElement prev) {

        if (curr instanceof PsiMethod) {
            return null;
        }

        PsiElement parent = curr.getParent();
        if (curr instanceof PsiContinueStatement) {
            PsiStatement continuedStatement = ((PsiContinueStatement) curr)
                    .findContinuedStatement();
            if (continuedStatement != null) {
                if (containsElement(method, continuedStatement, target)) {
                    return target;
                }
                return next(method, continuedStatement, target, curr);
            } else {
                return next(method, parent, target, curr);
            }
        } else if (curr instanceof PsiBreakStatement) {
            PsiStatement exitedStatement = ((PsiBreakStatement) curr).findExitedStatement();
            if (exitedStatement != null) {
                return next(method, exitedStatement, target, curr);
            } else {
                return next(method, parent, target, curr);
            }
        } else if (curr instanceof PsiReturnStatement) {
            return null;
        } else if (curr instanceof PsiLoopStatement && prev != null &&
                containsElement(method, curr, prev)) {
            // If we stepped *up* (from a last child nested in the loop) up to the loop
            // itself, mark all children in the loop as reachable since we're iterating
            if (containsElement(method, curr, target)) {
                return target;
            }
        }

        PsiElement sibling = curr.getNextSibling();
        while (sibling instanceof PsiWhiteSpace || sibling instanceof PsiJavaToken) {
            // Skip whitespaces and tokens such as PsiJavaToken.SEMICOLON etc
            sibling = sibling.getNextSibling();
        }
        if (sibling == null) {
            return next(method, parent, target, curr);
        }

        if (parent instanceof PsiIfStatement &&
                curr == ((PsiIfStatement)parent).getThenBranch()) {
            return next(method, parent, target, curr);
        } else if (parent instanceof PsiLoopStatement) {
            if (containsElement(method, parent, target)) {
                return target;
            }
        }

        return sibling;
    }

    private static boolean containsElement(@
            NonNull PsiMethod method,
            @NonNull PsiElement root,
            @NonNull PsiElement element) {
        //noinspection ConstantConditions
        while (element != null && element != method) {
            if (root.equals(element)) {
                return true;
            }

            element = element.getParent();
        }

        return false;
    }

    private static boolean containsElement(@
            NonNull UFunction method,
            @NonNull UElement root,
            @NonNull UElement element) {
        //noinspection ConstantConditions
        while (element != null && element != method) {
            if (root.equals(element)) {
                return true;
            }

            element = element.getParent();
        }

        return false;
    }
}
