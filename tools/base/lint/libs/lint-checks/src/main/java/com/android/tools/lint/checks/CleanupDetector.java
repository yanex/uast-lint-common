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

import static com.android.SdkConstants.CLASS_CONTENTPROVIDER;
import static com.android.SdkConstants.CLASS_CONTEXT;
import static com.android.SdkConstants.CLASS_RESOURCES;
import static com.android.tools.lint.detector.api.LintUtils.skipParentheses;
import static org.jetbrains.uast.util.UastExpressionUtils.isFunctionCall;

import com.android.SdkConstants;
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
import com.google.common.collect.Lists;

import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UBlockExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UDeclarationsExpression;
import org.jetbrains.uast.UDoWhileExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULoopExpression;
import org.jetbrains.uast.UQualifiedExpression;
import org.jetbrains.uast.UReturnExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UUnaryExpression;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UWhileExpression;
import org.jetbrains.uast.UastBinaryOperator;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.UastVariableKind;
import org.jetbrains.uast.expressions.UReferenceExpression;
import org.jetbrains.uast.java.JavaUAssertExpression;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Arrays;
import java.util.List;

/**
 * Checks for missing {@code recycle} calls on resources that encourage it, and
 * for missing {@code commit} calls on FragmentTransactions, etc.
 */
public class CleanupDetector extends Detector implements Detector.UastScanner {

    private static final Implementation IMPLEMENTATION = new Implementation(
            CleanupDetector.class,
            Scope.JAVA_FILE_SCOPE);

    /** Problems with missing recycle calls */
    public static final Issue RECYCLE_RESOURCE = Issue.create(
        "Recycle", //$NON-NLS-1$
        "Missing `recycle()` calls",

        "Many resources, such as TypedArrays, VelocityTrackers, etc., " +
        "should be recycled (with a `recycle()` call) after use. This lint check looks " +
        "for missing `recycle()` calls.",

        Category.PERFORMANCE,
        7,
        Severity.WARNING,
            IMPLEMENTATION);

    /** Problems with missing commit calls. */
    public static final Issue COMMIT_FRAGMENT = Issue.create(
            "CommitTransaction", //$NON-NLS-1$
            "Missing `commit()` calls",

            "After creating a `FragmentTransaction`, you typically need to commit it as well",

            Category.CORRECTNESS,
            7,
            Severity.WARNING,
            IMPLEMENTATION);

    /** The main issue discovered by this detector */
    public static final Issue SHARED_PREF = Issue.create(
            "CommitPrefEdits", //$NON-NLS-1$
            "Missing `commit()` on `SharedPreference` editor",

            "After calling `edit()` on a `SharedPreference`, you must call `commit()` " +
            "or `apply()` on the editor to save the results.",

            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            new Implementation(
                    CleanupDetector.class,
                    Scope.JAVA_FILE_SCOPE));

    // Target method names
    private static final String RECYCLE = "recycle";                                  //$NON-NLS-1$
    private static final String RELEASE = "release";                                  //$NON-NLS-1$
    private static final String OBTAIN = "obtain";                                    //$NON-NLS-1$
    private static final String SHOW = "show";                                        //$NON-NLS-1$
    private static final String ACQUIRE_CPC = "acquireContentProviderClient";         //$NON-NLS-1$
    private static final String OBTAIN_NO_HISTORY = "obtainNoHistory";                //$NON-NLS-1$
    private static final String OBTAIN_ATTRIBUTES = "obtainAttributes";               //$NON-NLS-1$
    private static final String OBTAIN_TYPED_ARRAY = "obtainTypedArray";              //$NON-NLS-1$
    private static final String OBTAIN_STYLED_ATTRIBUTES = "obtainStyledAttributes";  //$NON-NLS-1$
    private static final String BEGIN_TRANSACTION = "beginTransaction";               //$NON-NLS-1$
    private static final String COMMIT = "commit";                                    //$NON-NLS-1$
    private static final String APPLY = "apply";                                      //$NON-NLS-1$
    private static final String COMMIT_ALLOWING_LOSS = "commitAllowingStateLoss";     //$NON-NLS-1$
    private static final String QUERY = "query";                                      //$NON-NLS-1$
    private static final String RAW_QUERY = "rawQuery";                               //$NON-NLS-1$
    private static final String QUERY_WITH_FACTORY = "queryWithFactory";              //$NON-NLS-1$
    private static final String RAW_QUERY_WITH_FACTORY = "rawQueryWithFactory";       //$NON-NLS-1$
    private static final String CLOSE = "close";                                      //$NON-NLS-1$
    private static final String EDIT = "edit";                                        //$NON-NLS-1$

    private static final String MOTION_EVENT_CLS = "android.view.MotionEvent";        //$NON-NLS-1$
    private static final String PARCEL_CLS = "android.os.Parcel";                     //$NON-NLS-1$
    private static final String VELOCITY_TRACKER_CLS = "android.view.VelocityTracker";//$NON-NLS-1$
    private static final String DIALOG_FRAGMENT = "android.app.DialogFragment";       //$NON-NLS-1$
    private static final String DIALOG_V4_FRAGMENT =
            "android.support.v4.app.DialogFragment";                                  //$NON-NLS-1$
    private static final String FRAGMENT_MANAGER_CLS = "android.app.FragmentManager"; //$NON-NLS-1$
    private static final String FRAGMENT_MANAGER_V4_CLS =
            "android.support.v4.app.FragmentManager";                                 //$NON-NLS-1$
    private static final String FRAGMENT_TRANSACTION_CLS =
            "android.app.FragmentTransaction";                                        //$NON-NLS-1$
    private static final String FRAGMENT_TRANSACTION_V4_CLS =
            "android.support.v4.app.FragmentTransaction";                             //$NON-NLS-1$

    public static final String SURFACE_CLS = "android.view.Surface";
    public static final String SURFACE_TEXTURE_CLS = "android.graphics.SurfaceTexture";

    public static final String CONTENT_PROVIDER_CLIENT_CLS
            = "android.content.ContentProviderClient";

    public static final String CONTENT_RESOLVER_CLS = "android.content.ContentResolver";

    @SuppressWarnings("SpellCheckingInspection")
    public static final String SQLITE_DATABASE_CLS = "android.database.sqlite.SQLiteDatabase";
    public static final String CURSOR_CLS = "android.database.Cursor";

    public static final String ANDROID_CONTENT_SHARED_PREFERENCES =
            "android.content.SharedPreferences"; //$NON-NLS-1$
    private static final String ANDROID_CONTENT_SHARED_PREFERENCES_EDITOR =
            "android.content.SharedPreferences.Editor"; //$NON-NLS-1$

    /** Constructs a new {@link CleanupDetector} */
    public CleanupDetector() {
    }

    // ---- Implements UastScanner ----


    @Nullable
    @Override
    public List<String> getApplicableFunctionNames() {
        return Arrays.asList(
                // FragmentManager commit check
                BEGIN_TRANSACTION,

                // Recycle check
                OBTAIN, OBTAIN_NO_HISTORY,
                OBTAIN_STYLED_ATTRIBUTES,
                OBTAIN_ATTRIBUTES,
                OBTAIN_TYPED_ARRAY,

                // Release check
                ACQUIRE_CPC,

                // Cursor close check
                QUERY, RAW_QUERY, QUERY_WITH_FACTORY, RAW_QUERY_WITH_FACTORY,

                // SharedPreferences check
                EDIT
        );
    }

    @Nullable
    @Override
    public List<String> getApplicableConstructorTypes() {
        return Arrays.asList(SURFACE_TEXTURE_CLS, SURFACE_CLS);
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context,
            @Nullable UastVisitor visitor, @NonNull UCallExpression call,
            @NonNull UFunction function) {
        if (function.matchesName(BEGIN_TRANSACTION)) {
            checkTransactionCommits(context, call, function);
        } else if (function.matchesName(EDIT)) {
            checkEditorApplied(context, call, function);
        } else {
            checkResourceRecycled(context, call, function);
        }
    }

    @Override
    public void visitConstructor(@NonNull JavaContext context, @Nullable UastVisitor visitor,
            @NonNull UCallExpression node, @NonNull UFunction constructor) {
        UClass containingClass = UastUtils.getContainingClass(constructor);
        if (containingClass != null) {
            String type = containingClass.getFqName();
            if (type != null) {
                checkRecycled(context, node, type, RELEASE);
            }
        }
    }

    private static void checkResourceRecycled(@NonNull JavaContext context,
            @NonNull UCallExpression node, @NonNull UFunction method) {
        // Recycle detector
        UClass containingClass = UastUtils.getContainingClass(method);
        if (containingClass == null) {
            return;
        }
        if ((node.matchesFunctionName(OBTAIN) || node.matchesFunctionName(OBTAIN_NO_HISTORY)) &&
                containingClass.isSubclassOf(MOTION_EVENT_CLS, false)) {
            checkRecycled(context, node, MOTION_EVENT_CLS, RECYCLE);
        } else if (node.matchesFunctionName(OBTAIN) &&
                containingClass.isSubclassOf(PARCEL_CLS, false)) {
            checkRecycled(context, node, PARCEL_CLS, RECYCLE);
        } else if (node.matchesFunctionName(OBTAIN) &&
                containingClass.isSubclassOf(VELOCITY_TRACKER_CLS, false)) {
            checkRecycled(context, node, VELOCITY_TRACKER_CLS, RECYCLE);
        } else if ((node.matchesFunctionName(OBTAIN_STYLED_ATTRIBUTES)
                || node.matchesFunctionName(OBTAIN_ATTRIBUTES)
                || node.matchesFunctionName(OBTAIN_TYPED_ARRAY)) &&
                (containingClass.isSubclassOf(CLASS_CONTEXT, false) ||
                        containingClass.isSubclassOf(CLASS_RESOURCES, false))) {
            UType returnType = method.getReturnType();
            UClass cls = returnType != null ? returnType.resolveToClass(context) : null;
            if (cls != null && cls.matchesFqName(SdkConstants.CLS_TYPED_ARRAY)) {
                checkRecycled(context, node, SdkConstants.CLS_TYPED_ARRAY, RECYCLE);
            }
        } else if (node.matchesFunctionName(ACQUIRE_CPC)
                && containingClass.isSubclassOf(CONTENT_RESOLVER_CLS, false)) {
            checkRecycled(context, node, CONTENT_PROVIDER_CLIENT_CLS, RELEASE);
        } else if ((node.matchesFunctionName(QUERY)
                || node.matchesFunctionName(RAW_QUERY)
                || node.matchesFunctionName(QUERY_WITH_FACTORY)
                || node.matchesFunctionName(RAW_QUERY_WITH_FACTORY))
                && (containingClass.isSubclassOf(SQLITE_DATABASE_CLS, false) ||
                    containingClass.isSubclassOf(CONTENT_RESOLVER_CLS, false) ||
                    containingClass.isSubclassOf(CLASS_CONTENTPROVIDER, false) ||
                    containingClass.isSubclassOf(CONTENT_PROVIDER_CLIENT_CLS, false))) {
            // Other potential cursors-returning methods that should be tracked:
            //    android.app.DownloadManager#query
            //    android.content.ContentProviderClient#query
            //    android.content.ContentResolver#query
            //    android.database.sqlite.SQLiteQueryBuilder#query
            //    android.provider.Browser#getAllBookmarks
            //    android.provider.Browser#getAllVisitedUrls
            //    android.provider.DocumentsProvider#queryChildDocuments
            //    android.provider.DocumentsProvider#qqueryDocument
            //    android.provider.DocumentsProvider#queryRecentDocuments
            //    android.provider.DocumentsProvider#queryRoots
            //    android.provider.DocumentsProvider#querySearchDocuments
            //    android.provider.MediaStore$Images$Media#query
            //    android.widget.FilterQueryProvider#runQuery
            checkRecycled(context, node, CURSOR_CLS, CLOSE);
        }
    }

    private static void checkRecycled(
            @NonNull final JavaContext context,
            @NonNull UCallExpression node,
            @NonNull final String recycleType,
            @NonNull final String recycleName) {
        UVariable boundVariable = getVariableElement(node, context);
        if (boundVariable == null) {
            return;
        }

        UFunction method = UastUtils.getContainingFunction(node);
        if (method == null) {
            return;
        }
        FinishVisitor visitor = new FinishVisitor(context, boundVariable) {
            @Override
            protected boolean isCleanupCall(@NonNull UCallExpression call) {
                if (!call.matchesFunctionName(recycleName)) {
                    return false;
                }
                UFunction method = call.resolve(mContext);
                if (method != null) {
                    UClass containingClass = UastUtils.getContainingClassOrEmpty(method);
                    if (containingClass.isSubclassOf(recycleType, false)) {
                        // Yes, called the right recycle() method; now make sure
                        // we're calling it on the right variable
                        UExpression operand = UastUtils.getReceiver(call);
                        if (operand instanceof UReferenceExpression) {
                            UElement resolved = ((UReferenceExpression) operand).resolve(mContext);
                            //noinspection SuspiciousMethodCalls
                            if (resolved != null && mVariables.contains(resolved)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
        };

        method.accept(visitor);
        if (visitor.isCleanedUp() || visitor.variableEscapes()) {
            return;
        }

        String className = recycleType.substring(recycleType.lastIndexOf('.') + 1);
        String message;
        if (RECYCLE.equals(recycleName)) {
            message = String.format(
                    "This `%1$s` should be recycled after use with `#recycle()`", className);
        } else {
            message = String.format(
                    "This `%1$s` should be freed up after use with `#%2$s()`", className,
                    recycleName);
        }

        UElement locationNode = node instanceof UCallExpression ?
                ((UCallExpression) node).getFunctionNameElement() : node;
        if (locationNode == null) {
            locationNode = node;
        }
        Location location = context.getLocation(locationNode);
        context.report(RECYCLE_RESOURCE, node, location, message);
    }

    private static void checkTransactionCommits(@NonNull JavaContext context,
            @NonNull UCallExpression node, @NonNull UFunction calledMethod) {
        if (isBeginTransaction(context, calledMethod)) {
            UVariable boundVariable = getVariableElement(node, true, context);
            if (boundVariable == null && isCommittedInChainedCalls(context, node)) {
                return;
            }

            if (boundVariable != null) {
                UFunction method = UastUtils.getContainingFunction(node);
                if (method == null) {
                    return;
                }

                FinishVisitor commitVisitor = new FinishVisitor(context, boundVariable) {
                    @Override
                    protected boolean isCleanupCall(@NonNull UCallExpression call) {
                        if (isTransactionCommitMethodCall(mContext, call)) {
                            List<UExpression> callChain = UastUtils.getQualifiedChain(call);
                            if (callChain.isEmpty()) {
                                return false;
                            }
                            UExpression maybeVariable = callChain.get(0);
                            if (maybeVariable != null) {
                                UDeclaration resolved = UastUtils.resolveIfCan(maybeVariable, mContext);
                                //noinspection SuspiciousMethodCalls
                                if (resolved != null && mVariables.contains(resolved)) {
                                    return true;
                                }
                            }
                        } else if (isShowFragmentMethodCall(mContext, call)) {
                            if (call.getValueArgumentCount() == 2) {
                                UExpression first = call.getValueArguments().get(0);
                                UDeclaration resolved = UastUtils.resolveIfCan(first, mContext);
                                //noinspection SuspiciousMethodCalls
                                if (resolved != null && mVariables.contains(resolved)) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    }
                };

                method.accept(commitVisitor);
                if (commitVisitor.isCleanedUp() || commitVisitor.variableEscapes()) {
                    return;
                }
            }

            String message = "This transaction should be completed with a `commit()` call";
            context.report(COMMIT_FRAGMENT, node,
                    context.getLocation(node.getFunctionNameElement()), message);
        }
    }

    private static boolean isCommittedInChainedCalls(@NonNull JavaContext context,
            @NonNull UCallExpression node) {
        // Look for chained calls since the FragmentManager methods all return "this"
        // to allow constructor chaining, e.g.
        //    getFragmentManager().beginTransaction().addToBackStack("test")
        //            .disallowAddToBackStack().hide(mFragment2).setBreadCrumbShortTitle("test")
        //            .show(mFragment2).setCustomAnimations(0, 0).commit();
        List<UExpression> chain = UastUtils.getQualifiedChain(node);
        if (!chain.isEmpty()) {
            UExpression lastExpression = chain.get(chain.size() - 1);
            if (lastExpression instanceof UCallExpression) {
                UCallExpression methodInvocation = (UCallExpression) lastExpression;
                if (isTransactionCommitMethodCall(context, methodInvocation)
                        || isShowFragmentMethodCall(context, methodInvocation)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isTransactionCommitMethodCall(@NonNull JavaContext context,
            @NonNull UCallExpression call) {

        return (call.matchesFunctionName(COMMIT) || call.matchesFunctionName(COMMIT_ALLOWING_LOSS)) &&
                isMethodOnFragmentClass(context, call,
                        FRAGMENT_TRANSACTION_CLS,
                        FRAGMENT_TRANSACTION_V4_CLS,
                        true);
    }

    private static boolean isShowFragmentMethodCall(@NonNull JavaContext context,
            @NonNull UCallExpression call) {
        return call.matchesFunctionName(SHOW) && isMethodOnFragmentClass(context, call,
                DIALOG_FRAGMENT, DIALOG_V4_FRAGMENT, true);
    }

    private static boolean isMethodOnFragmentClass(
            @NonNull JavaContext context,
            @NonNull UCallExpression call,
            @NonNull String fragmentClass,
            @NonNull String v4FragmentClass,
            boolean returnForUnresolved) {
        UFunction function = call.resolve(context);
        if (function != null) {
            UClass containingClass = UastUtils.getContainingClassOrEmpty(function);
            return containingClass.isSubclassOf(fragmentClass, false) ||
                    containingClass.isSubclassOf(v4FragmentClass, false);
        } else {
            // If we *can't* resolve the function call, caller can decide
            // whether to consider the function called or not
            return returnForUnresolved;
        }
    }

    private static void checkEditorApplied(@NonNull JavaContext context,
            @NonNull UCallExpression node, @NonNull UFunction calledMethod) {
        if (isSharedEditorCreation(calledMethod)) {
            UVariable boundVariable = getVariableElement(node, true, context);
            if (isEditorCommittedInChainedCalls(context, node)) {
                return;
            }

            if (boundVariable != null) {
                UFunction method = UastUtils.getContainingFunction(node);
                if (method == null) {
                    return;
                }

                FinishVisitor commitVisitor = new FinishVisitor(context, boundVariable) {
                    @Override
                    protected boolean isCleanupCall(@NonNull UCallExpression call) {
                        if (isEditorApplyMethodCall(mContext, call)
                                || isEditorCommitMethodCall(mContext, call)) {
                            UExpression operand = UastUtils.getReceiver(call);
                            if (operand != null) {
                                UDeclaration resolved = UastUtils.resolveIfCan(operand, mContext);
                                //noinspection SuspiciousMethodCalls
                                if (resolved != null && mVariables.contains(resolved)) {
                                    return true;
                                } else if (resolved instanceof UFunction
                                        && operand instanceof UCallExpression
                                        && isCommittedInChainedCalls(mContext,
                                        (UCallExpression) operand)) {
                                    // Check that the target of the committed chains is the
                                    // right variable!
                                    while (operand instanceof UCallExpression) {
                                        operand = UastUtils.getReceiver((UCallExpression) operand);
                                    }
                                    if (operand instanceof UReferenceExpression) {
                                        resolved = ((UReferenceExpression) operand).resolve(mContext);
                                        //noinspection SuspiciousMethodCalls
                                        if (resolved != null && mVariables.contains(resolved)) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                        return false;
                    }
                };

                method.accept(commitVisitor);
                if (commitVisitor.isCleanedUp() || commitVisitor.variableEscapes()) {
                    return;
                }
            } else if (UastUtils.getParentOfType(node, UReturnExpression.class) != null) {
                // Allocation is in a return statement
                return;
            }

            String message = "`SharedPreferences.edit()` without a corresponding `commit()` or "
                    + "`apply()` call";
            context.report(SHARED_PREF, node, context.getLocation(node), message);
        }
    }

    private static boolean isSharedEditorCreation(@NonNull UFunction function) {
        if (function.matchesName(EDIT)) {
            UClass containingClass = UastUtils.getContainingClassOrEmpty(function);
            return containingClass.isSubclassOf(ANDROID_CONTENT_SHARED_PREFERENCES, false);
        }

        return false;
    }

    private static boolean isEditorCommittedInChainedCalls(@NonNull JavaContext context,
            @NonNull UCallExpression node) {
        List<UExpression> chain = UastUtils.getQualifiedChain(node);
        if (!chain.isEmpty()) {
            UExpression lastExpression = chain.get(chain.size() - 1);
            if (lastExpression instanceof UCallExpression) {
                UCallExpression methodInvocation = (UCallExpression) lastExpression;
                if (isEditorCommitMethodCall(context, methodInvocation)
                        || isEditorApplyMethodCall(context, methodInvocation)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isEditorCommitMethodCall(@NonNull JavaContext context,
            @NonNull UCallExpression call) {
        if (call.matchesFunctionName(COMMIT)) {
            UFunction method = call.resolve(context);
            if (method != null) {
                UClass containingClass = UastUtils.getContainingClassOrEmpty(method);
                if (containingClass.isSubclassOf(ANDROID_CONTENT_SHARED_PREFERENCES_EDITOR, false)) {
                    suggestApplyIfApplicable(context, call);
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isEditorApplyMethodCall(@NonNull JavaContext context,
            @NonNull UCallExpression call) {
        if (call.matchesFunctionName(APPLY)) {
            UFunction method = call.resolve(context);
            if (method != null) {
                UClass containingClass = UastUtils.getContainingClassOrEmpty(method);
                return containingClass.isSubclassOf(
                        ANDROID_CONTENT_SHARED_PREFERENCES_EDITOR, false);
            }
        }

        return false;
    }

    private static void suggestApplyIfApplicable(@NonNull JavaContext context,
            @NonNull UCallExpression node) {
        if (context.getProject().getMinSdkVersion().getApiLevel() >= 9) {
            // See if the return value is read: can only replace commit with
            // apply if the return value is not considered

            UElement qualifiedNode = node;
            UElement parent = skipParentheses(node.getParent());
            while (parent instanceof UReferenceExpression) {
                qualifiedNode = parent;
                parent = skipParentheses(parent.getParent());
            }
            boolean returnValueIgnored = true;

            if (parent instanceof UCallExpression
                    || parent instanceof UVariable
                    || parent instanceof UBinaryExpression
                    || parent instanceof UUnaryExpression
                    || parent instanceof UReturnExpression) {
                returnValueIgnored = false;
            } else if (parent instanceof UIfExpression) {
                UExpression condition = ((UIfExpression) parent).getCondition();
                returnValueIgnored = !condition.equals(qualifiedNode);
            } else if (parent instanceof UWhileExpression) {
                UExpression condition = ((UWhileExpression) parent).getCondition();
                returnValueIgnored = !condition.equals(qualifiedNode);
            } else if (parent instanceof UDoWhileExpression) {
                UExpression condition = ((UDoWhileExpression) parent).getCondition();
                returnValueIgnored = !condition.equals(qualifiedNode);
            }

            if (returnValueIgnored) {
                String message = "Consider using `apply()` instead; `commit` writes "
                        + "its data to persistent storage immediately, whereas "
                        + "`apply` will handle it in the background";
                context.report(SHARED_PREF, node, context.getLocation(node), message);
            }
        }
    }

    /** Returns the variable the expression is assigned to, if any */
    @Nullable
    private static UVariable getVariableElement(
            @NonNull UCallExpression callExpression, UastContext context) {
        return getVariableElement(callExpression, false, context);
    }

    @Nullable
    private static UVariable getVariableElement(@NonNull UCallExpression callExpression,
            boolean allowChainedCalls, UastContext context) {
        UElement parent = skipParentheses(
                UastUtils.getQualifiedParentOrThis(callExpression).getParent());

        // Handle some types of chained calls; e.g. you might have
        //    var = prefs.edit().put(key,value)
        // and here we want to skip past the put call
        if (allowChainedCalls) {
            while (true) {
                if ((parent instanceof UQualifiedExpression)) {
                    UElement parentParent = skipParentheses(parent.getParent());
                    if ((parentParent instanceof UQualifiedExpression)) {
                        parent = skipParentheses(parentParent.getParent());
                    } else if (parentParent instanceof UVariable
                            || parentParent instanceof UBinaryExpression) {
                        parent = parentParent;
                        break;
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        if (parent instanceof UBinaryExpression) {
            UBinaryExpression binaryExpression = (UBinaryExpression) parent;
            if (binaryExpression.getOperator() instanceof UastBinaryOperator.AssignOperator) {
                UExpression lhs = binaryExpression.getLeftOperand();
                if (lhs instanceof UReferenceExpression) {
                    UElement resolved = ((UReferenceExpression) lhs).resolve(context);
                    if (resolved instanceof UVariable) {
                        UVariable variable = (UVariable) resolved;
                        // e.g. local variable, parameter - but not a field
                        if (variable.getKind() != UastVariableKind.MEMBER) {
                            return (UVariable) resolved;
                        }
                    }
                }
            }
        } else if (parent instanceof UVariable
                && ((UVariable) parent).getKind() != UastVariableKind.MEMBER) {
            return (UVariable) parent;
        }

        return null;
    }

    private static boolean isBeginTransaction(@NonNull JavaContext context, @NonNull UFunction method) {
        if (method.matchesName(BEGIN_TRANSACTION)) {
            UClass containingClass = UastUtils.getContainingClassOrEmpty(method);
            if (containingClass.isSubclassOf(FRAGMENT_MANAGER_CLS, false)
                    || containingClass.isSubclassOf(FRAGMENT_MANAGER_V4_CLS, false)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Visitor which checks whether an operation is "finished"; in the case
     * of a FragmentTransaction we're looking for a "commit" call; in the
     * case of a TypedArray we're looking for a "recycle", call, in the
     * case of a database cursor we're looking for a "close" call, etc.
     */
    private abstract static class FinishVisitor extends AbstractUastVisitor {
        protected final JavaContext mContext;
        protected final List<UVariable> mVariables;
        private final UVariable mOriginalVariableNode;

        private boolean mContainsCleanup;
        private boolean mEscapes;

        FinishVisitor(JavaContext context, @NonNull UVariable variableNode) {
            mContext = context;
            mOriginalVariableNode = variableNode;
            mVariables = Lists.newArrayList(variableNode);
        }

        boolean isCleanedUp() {
            return mContainsCleanup;
        }

        boolean variableEscapes() {
            return mEscapes;
        }

        @Override
        public boolean visitElement(UElement node) {
            return mContainsCleanup || super.visitElement(node);
        }

        abstract boolean isCleanupCall(@NonNull UCallExpression call);

        @Override
        public boolean visitCallExpression(UCallExpression call) {
            if (mContainsCleanup) {
                return super.visitCallExpression(call);
            }

            boolean ret = super.visitCallExpression(call);

            // Look for escapes
            if (!mEscapes) {
                for (UExpression expression : call.getValueArguments()) {
                    if (expression instanceof UReferenceExpression) {
                        UDeclaration resolved = ((UReferenceExpression) expression).resolve(mContext);
                        //noinspection SuspiciousMethodCalls
                        if (resolved != null && mVariables.contains(resolved)) {
                            boolean wasEscaped = mEscapes;
                            mEscapes = true;

                            // Special case: MotionEvent.obtain(MotionEvent): passing in an
                            // event here does not recycle the event, and we also know it
                            // doesn't escape
                            if (call.matchesFunctionName(OBTAIN)) {
                                UFunction method = call.resolve(mContext);
                                if (method != null) {
                                    if (method.matchesNameWithContaining(MOTION_EVENT_CLS, OBTAIN)) {
                                        mEscapes = wasEscaped;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isCleanupCall(call)) {
                mContainsCleanup = true;
            }

            return ret;
        }

        @Override
        public boolean visitVariable(UVariable variable) {
            boolean ret = super.visitVariable(variable);

            UExpression initializer = variable.getInitializer();
            if (initializer instanceof UReferenceExpression) {
                UDeclaration resolved = ((UReferenceExpression) initializer).resolve(mContext);
                //noinspection SuspiciousMethodCalls
                if (resolved != null && mVariables.contains(resolved)) {
                    mVariables.add(variable);
                }
            }

            return ret;
        }

        @Override
        public boolean visitBinaryExpression(UBinaryExpression node) {
            boolean ret = super.visitBinaryExpression(node);

            if (node.getOperator() instanceof UastBinaryOperator.AssignOperator) {
                visitAssignmentExpression(node);
            }

            return ret;
        }

        private void visitAssignmentExpression(UBinaryExpression expression) {
            // TEMPORARILY DISABLED; see testDatabaseCursorReassignment
            // This can result in some false positives right now. Play it
            // safe instead.
            boolean clearLhs = false;

            UExpression rhs = expression.getRightOperand();
            if (rhs instanceof UReferenceExpression) {
                UElement resolved = ((UReferenceExpression) rhs).resolve(mContext);
                //noinspection SuspiciousMethodCalls
                if (resolved != null && mVariables.contains(resolved)) {
                    clearLhs = false;
                    UElement lhs = UastUtils.resolveIfCan(expression.getLeftOperand(), mContext);
                    if (lhs instanceof UVariable
                            && ((UVariable) lhs).getKind() == UastVariableKind.LOCAL_VARIABLE) {
                        mVariables.add((UVariable) lhs);
                    } else if (lhs instanceof UVariable) {
                        mEscapes = true;
                    }
                }
            }

            //noinspection ConstantConditions
            if (clearLhs) {
                // If we reassign one of the variables, clear it out
                UElement lhs = UastUtils.resolveIfCan(expression.getLeftOperand(), mContext);
                //noinspection SuspiciousMethodCalls
                if (lhs != null && !lhs.equals(mOriginalVariableNode)
                        && mVariables.contains(lhs)) {
                    //noinspection SuspiciousMethodCalls
                    mVariables.remove(lhs);
                }
            }
        }

        @Override
        public boolean visitReturnExpression(UReturnExpression node) {
            UExpression returnValue = node.getReturnExpression();
            if (returnValue instanceof UReferenceExpression) {
                UDeclaration resolved = ((UReferenceExpression) returnValue).resolve(mContext);
                //noinspection SuspiciousMethodCalls
                if (resolved != null && mVariables.contains(resolved)) {
                    mEscapes = true;
                }
            }

            return super.visitReturnExpression(node);
        }
    }
}
