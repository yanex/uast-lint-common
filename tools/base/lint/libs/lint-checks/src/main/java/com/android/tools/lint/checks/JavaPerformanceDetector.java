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

import static com.android.SdkConstants.SUPPORT_LIB_ARTIFACT;
import static com.android.tools.lint.client.api.JavaParser.TYPE_BOOLEAN;
import static com.android.tools.lint.client.api.JavaParser.TYPE_BOOLEAN_WRAPPER;
import static com.android.tools.lint.client.api.JavaParser.TYPE_BYTE_WRAPPER;
import static com.android.tools.lint.client.api.JavaParser.TYPE_CHARACTER_WRAPPER;
import static com.android.tools.lint.client.api.JavaParser.TYPE_DOUBLE_WRAPPER;
import static com.android.tools.lint.client.api.JavaParser.TYPE_FLOAT_WRAPPER;
import static com.android.tools.lint.client.api.JavaParser.TYPE_INT;
import static com.android.tools.lint.client.api.JavaParser.TYPE_INTEGER_WRAPPER;
import static com.android.tools.lint.client.api.JavaParser.TYPE_LONG_WRAPPER;
import static com.android.tools.lint.detector.api.LintUtils.skipParentheses;
import static org.jetbrains.uast.util.UTypeConstraint.PRIMITIVE_BOOLEAN;
import static org.jetbrains.uast.util.UTypeConstraint.PRIMITIVE_INT;
import static org.jetbrains.uast.util.UastSignatureChecker.matchesSignature;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.JavaEvaluator;
import com.android.tools.lint.client.api.UastLintUtils;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.TextFormat;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;

import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.UParenthesizedExpression;
import org.jetbrains.uast.UPrefixExpression;
import org.jetbrains.uast.UQualifiedExpression;
import org.jetbrains.uast.USimpleReferenceExpression;
import org.jetbrains.uast.USuperExpression;
import org.jetbrains.uast.UThisExpression;
import org.jetbrains.uast.UThrowExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UastBinaryOperator;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.util.UTypeConstraint;
import org.jetbrains.uast.util.UastExpressionUtils;
import org.jetbrains.uast.util.UastSignatureChecker;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Looks for performance issues in Java files, such as memory allocations during
 * drawing operations and using HashMap instead of SparseArray.
 */
public class JavaPerformanceDetector extends Detector implements Detector.UastScanner {

    private static final Implementation IMPLEMENTATION = new Implementation(
            JavaPerformanceDetector.class,
            Scope.JAVA_FILE_SCOPE);

    /** Allocating objects during a paint method */
    public static final Issue PAINT_ALLOC = Issue.create(
            "DrawAllocation", //$NON-NLS-1$
            "Memory allocations within drawing code",

            "You should avoid allocating objects during a drawing or layout operation. These " +
            "are called frequently, so a smooth UI can be interrupted by garbage collection " +
            "pauses caused by the object allocations.\n" +
            "\n" +
            "The way this is generally handled is to allocate the needed objects up front " +
            "and to reuse them for each drawing operation.\n" +
            "\n" +
            "Some methods allocate memory on your behalf (such as `Bitmap.create`), and these " +
            "should be handled in the same way.",

            Category.PERFORMANCE,
            9,
            Severity.WARNING,
            IMPLEMENTATION);

    /** Using HashMaps where SparseArray would be better */
    public static final Issue USE_SPARSE_ARRAY = Issue.create(
            "UseSparseArrays", //$NON-NLS-1$
            "HashMap can be replaced with SparseArray",

            "For maps where the keys are of type integer, it's typically more efficient to " +
            "use the Android `SparseArray` API. This check identifies scenarios where you might " +
            "want to consider using `SparseArray` instead of `HashMap` for better performance.\n" +
            "\n" +
            "This is *particularly* useful when the value types are primitives like ints, " +
            "where you can use `SparseIntArray` and avoid auto-boxing the values from `int` to " +
            "`Integer`.\n" +
            "\n" +
            "If you need to construct a `HashMap` because you need to call an API outside of " +
            "your control which requires a `Map`, you can suppress this warning using for " +
            "example the `@SuppressLint` annotation.",

            Category.PERFORMANCE,
            4,
            Severity.WARNING,
            IMPLEMENTATION);

    /** Using {@code new Integer()} instead of the more efficient {@code Integer.valueOf} */
    public static final Issue USE_VALUE_OF = Issue.create(
            "UseValueOf", //$NON-NLS-1$
            "Should use `valueOf` instead of `new`",

            "You should not call the constructor for wrapper classes directly, such as" +
            "`new Integer(42)`. Instead, call the `valueOf` factory method, such as " +
            "`Integer.valueOf(42)`. This will typically use less memory because common integers " +
            "such as 0 and 1 will share a single instance.",

            Category.PERFORMANCE,
            4,
            Severity.WARNING,
            IMPLEMENTATION);

    static final String ON_MEASURE = "onMeasure";
    static final String ON_DRAW = "onDraw";
    static final String ON_LAYOUT = "onLayout";
    private static final String LAYOUT = "layout";
    private static final String HASH_MAP = "java.util.HashMap";
    private static final String SPARSE_ARRAY = "android.util.SparseArray";
    public static final String CLASS_CANVAS = "android.graphics.Canvas";

    /** Constructs a new {@link JavaPerformanceDetector} check */
    public JavaPerformanceDetector() {
    }

    // ---- Implements UastScanner ----

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        List<Class<? extends UElement>> types = new ArrayList<Class<? extends UElement>>(3);
        types.add(UCallExpression.class);
        types.add(UFunction.class);
        return types;
    }

    @Override
    public UastVisitor createUastVisitor(@NonNull JavaContext context) {
        return new PerformanceVisitor(context);
    }

    private static class PerformanceVisitor extends AbstractUastVisitor {
        private final JavaContext mContext;
        private final boolean mCheckMaps;
        private final boolean mCheckAllocations;
        private final boolean mCheckValueOf;
        /** Whether allocations should be "flagged" in the current method */
        private boolean mFlagAllocations;

        PerformanceVisitor(JavaContext context) {
            mContext = context;

            mCheckAllocations = context.isEnabled(PAINT_ALLOC);
            mCheckMaps = context.isEnabled(USE_SPARSE_ARRAY);
            mCheckValueOf = context.isEnabled(USE_VALUE_OF);
        }

        @Override
        public boolean visitFunction(UFunction node) {
            mFlagAllocations = isBlockedAllocationMethod(node);
            return super.visitFunction(node);
        }

        private void visitConstructorCallExpression(UCallExpression node) {
            String typeName = null;
            if (mCheckMaps || mCheckValueOf) {
                typeName = node.resolveTypeOrEmpty(mContext).getFqName();
            }

            if (mCheckMaps) {
                // TODO: Should we handle factory method constructions of HashMaps as well,
                // e.g. via Guava? This is a bit trickier since we need to infer the type
                // arguments from the calling context.
                if (HASH_MAP.equals(typeName)) {
                    checkHashMap(node);
                } else if (SPARSE_ARRAY.equals(typeName)) {
                    checkSparseArray(node);
                }
            }

            if (mCheckValueOf) {
                if (typeName != null
                        && (typeName.equals(TYPE_INTEGER_WRAPPER)
                        || typeName.equals(TYPE_BOOLEAN_WRAPPER)
                        || typeName.equals(TYPE_FLOAT_WRAPPER)
                        || typeName.equals(TYPE_CHARACTER_WRAPPER)
                        || typeName.equals(TYPE_LONG_WRAPPER)
                        || typeName.equals(TYPE_DOUBLE_WRAPPER)
                        || typeName.equals(TYPE_BYTE_WRAPPER))
                        //&& node.astTypeReference().astParts().size() == 1
                        && node.getValueArgumentCount() == 1) {
                    String argument = node.getValueArguments().get(0).originalString();
                    mContext.report(USE_VALUE_OF, node, mContext.getLocation(node),
                            getUseValueOfErrorMessage(typeName, argument));
                }
            }

            if (mFlagAllocations
                    && !(skipParentheses(node.getParent()) instanceof UThrowExpression)
                    && mCheckAllocations) {
                // Make sure we're still inside the method declaration that marked
                // mInDraw as true, in case we've left it and we're in a static
                // block or something:
                UFunction function = UastUtils.getParentOfType(node, UFunction.class);
                if (function != null && isBlockedAllocationMethod(function)
                        && !isLazilyInitialized(node)) {
                    reportAllocation(node);
                }
            }
        }

        private void reportAllocation(UElement node) {
            mContext.report(PAINT_ALLOC, node, mContext.getLocation(node),
                "Avoid object allocations during draw/layout operations (preallocate and " +
                "reuse instead)");
        }

        @Override
        public boolean visitCallExpression(UCallExpression node) {
            if (UastExpressionUtils.isFunctionCall(node)) {
                visitMethodCallExpression(node);
            } else if (UastExpressionUtils.isConstructorCall(node)) {
                visitConstructorCallExpression(node);
            }

            return super.visitCallExpression(node);
        }

        private void visitMethodCallExpression(UCallExpression node) {
            if (!mFlagAllocations) {
                return;
            }
            UExpression receiver = UastUtils.getReceiver(node);
            if (receiver == null) {
                return;
            }

            String functionName = node.getFunctionName();
            if (functionName == null) {
                return;
            }

            // Look for forbidden methods
            if (functionName.equals("createBitmap")                              //$NON-NLS-1$
                    || functionName.equals("createScaledBitmap")) {              //$NON-NLS-1$
                UFunction method = node.resolve(mContext);
                if (method != null && JavaEvaluator.isMemberInClass(method,
                        "android.graphics.Bitmap") && !isLazilyInitialized(node)) {
                    reportAllocation(node);
                }
            } else if (functionName.startsWith("decode")) {                      //$NON-NLS-1$
                // decodeFile, decodeByteArray, ...
                UFunction method = node.resolve(mContext);
                if (method != null && JavaEvaluator.isMemberInClass(method,
                        "android.graphics.BitmapFactory") && !isLazilyInitialized(node)) {
                    reportAllocation(node);
                }
            } else if (functionName.equals("getClipBounds")) {                   //$NON-NLS-1$
                if (node.getValueArguments().isEmpty()) {
                    mContext.report(PAINT_ALLOC, node, mContext.getLocation(node),
                            "Avoid object allocations during draw operations: Use " +
                            "`Canvas.getClipBounds(Rect)` instead of `Canvas.getClipBounds()` " +
                            "which allocates a temporary `Rect`");
                }
            }
        }

        /**
         * Check whether the given invocation is done as a lazy initialization,
         * e.g. {@code if (foo == null) foo = new Foo();}.
         * <p>
         * This tries to also handle the scenario where the check is on some
         * <b>other</b> variable - e.g.
         * <pre>
         *    if (foo == null) {
         *        foo == init1();
         *        bar = new Bar();
         *    }
         * </pre>
         * or
         * <pre>
         *    if (!initialized) {
         *        initialized = true;
         *        bar = new Bar();
         *    }
         * </pre>
         */
        private static boolean isLazilyInitialized(UElement node) {
            UElement curr = node.getParent();
            while (curr != null) {
                if (curr instanceof UFunction) {
                    return false;
                } else if (curr instanceof UIfExpression) {
                    UIfExpression ifNode = (UIfExpression) curr;
                    // See if the if block represents a lazy initialization:
                    // compute all variable names seen in the condition
                    // (e.g. for "if (foo == null || bar != foo)" the result is "foo,bar"),
                    // and then compute all variables assigned to in the if body,
                    // and if there is an overlap, we'll consider the whole if block
                    // guarded (so lazily initialized and an allocation we won't complain
                    // about.)
                    List<String> assignments = new ArrayList<String>();
                    AssignmentTracker visitor = new AssignmentTracker(assignments);
                    if (ifNode.getThenBranch() != null) {
                        ifNode.getThenBranch().accept(visitor);
                    }
                    if (!assignments.isEmpty()) {
                        List<String> references = new ArrayList<String>();
                        addReferencedVariables(references, ifNode.getCondition());
                        if (!references.isEmpty()) {
                            SetView<String> intersection = Sets.intersection(
                                    new HashSet<String>(assignments),
                                    new HashSet<String>(references));
                            return !intersection.isEmpty();
                        }
                    }
                    return false;

                }
                curr = curr.getParent();
            }

            return false;
        }

        /** Adds any variables referenced in the given expression into the given list */
        private static void addReferencedVariables(
                @NonNull Collection<String> variables,
                @Nullable UExpression expression) {
            if (expression instanceof UBinaryExpression) {
                UBinaryExpression binary = (UBinaryExpression) expression;
                addReferencedVariables(variables, binary.getLeftOperand());
                addReferencedVariables(variables, binary.getRightOperand());
            } else if (expression instanceof UPrefixExpression) {
                UPrefixExpression unary = (UPrefixExpression) expression;
                addReferencedVariables(variables, unary.getOperand());
            } else if (expression instanceof UParenthesizedExpression) {
                UParenthesizedExpression exp = (UParenthesizedExpression) expression;
                addReferencedVariables(variables, exp.getExpression());
            } else if (expression instanceof USimpleReferenceExpression) {
                USimpleReferenceExpression reference = (USimpleReferenceExpression) expression;
                variables.add(reference.getIdentifier());
            } else if (expression instanceof UQualifiedExpression) {
                UQualifiedExpression ref = (UQualifiedExpression) expression;
                UExpression qualifier = ref.getReceiver();
                if (qualifier instanceof UThisExpression || qualifier instanceof USuperExpression) {
                    String identifier = ref.getIdentifier();
                    if (identifier != null) {
                        variables.add(identifier);
                    }
                }
            }
        }

        /**
         * Returns whether the given method declaration represents a method
         * where allocating objects is not allowed for performance reasons
         */
        private static boolean isBlockedAllocationMethod(@NonNull UFunction node) {
            return isOnDrawMethod(node)
                    || isOnMeasureMethod(node)
                    || isOnLayoutMethod(node)
                    || isLayoutMethod(node);
        }

        /**
         * Returns true if this method looks like it's overriding android.view.View's
         * {@code protected void onDraw(Canvas canvas)}
         */
        private static boolean isOnDrawMethod(@NonNull UFunction node) {
            return ON_DRAW.equals(node.getName())
                    && matchesSignature(node, UTypeConstraint.make(CLASS_CANVAS));
        }

        /**
         * Returns true if this method looks like it's overriding
         * android.view.View's
         * {@code protected void onLayout(boolean changed, int left, int top,
         *      int right, int bottom)}
         */
        private static boolean isOnLayoutMethod(@NonNull UFunction node) {
            return ON_LAYOUT.equals(node.getName()) && matchesSignature(node,
                    PRIMITIVE_BOOLEAN, PRIMITIVE_INT, PRIMITIVE_INT, PRIMITIVE_INT, PRIMITIVE_INT);
        }

        /**
         * Returns true if this method looks like it's overriding android.view.View's
         * {@code protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)}
         */
        private static boolean isOnMeasureMethod(@NonNull UFunction node) {
            return ON_MEASURE.equals(node.getName()) && matchesSignature(node,
                    PRIMITIVE_INT, PRIMITIVE_INT);
        }

        /**
         * Returns true if this method looks like it's overriding android.view.View's
         * {@code public void layout(int l, int t, int r, int b)}
         */
        private static boolean isLayoutMethod(@NonNull UFunction node) {
            return node.matchesName(LAYOUT) && matchesSignature(node,
                    PRIMITIVE_INT, PRIMITIVE_INT, PRIMITIVE_INT, PRIMITIVE_INT);
        }

        /**
         * Checks whether the given constructor call and type reference refers
         * to a HashMap constructor call that is eligible for replacement by a
         * SparseArray call instead
         */
        private void checkHashMap(@NonNull UCallExpression node) {
            if (node.getTypeArgumentCount() != 2) {
                return;
            }

            List<UType> types = node.getTypeArguments();
            UType first = types.get(0);
            int minSdk = mContext.getMainProject().getMinSdk();
            if (first.matchesFqName(TYPE_INTEGER_WRAPPER) || first.matchesFqName(TYPE_BYTE_WRAPPER)) {
                UType second = types.get(1);
                if (second.matchesFqName(TYPE_INTEGER_WRAPPER)) {
                    mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                            "Use new `SparseIntArray(...)` instead for better performance");
                } else if (second.matchesFqName(TYPE_LONG_WRAPPER) && minSdk >= 18) {
                    mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                            "Use `new SparseLongArray(...)` instead for better performance");
                } else if (second.matchesFqName(TYPE_BOOLEAN_WRAPPER)) {
                    mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                            "Use `new SparseBooleanArray(...)` instead for better performance");
                } else {
                    mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                            String.format(
                                    "Use `new SparseArray<%1$s>(...)` instead for better performance",
                                    second.getName()));
                }
            } else if (first.matchesFqName(TYPE_LONG_WRAPPER) && (minSdk >= 16 ||
                    Boolean.TRUE == mContext.getMainProject().dependsOn(
                            SUPPORT_LIB_ARTIFACT))) {
                boolean useBuiltin = minSdk >= 16;
                String message = useBuiltin ?
                        "Use `new LongSparseArray(...)` instead for better performance" :
                        "Use `new android.support.v4.util.LongSparseArray(...)` instead for better performance";
                mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                        message);
            }
        }

        private void checkSparseArray(@NonNull UCallExpression node) {
            if (node.getTypeArgumentCount() != 1) {
                return;
            }

            List<UType> types = node.getTypeArguments();
            UType first = types.get(0);
            if (first.matchesFqName(TYPE_INTEGER_WRAPPER)) {
                mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                        "Use `new SparseIntArray(...)` instead for better performance");
            } else if (first.matchesFqName(TYPE_BOOLEAN_WRAPPER)) {
                mContext.report(USE_SPARSE_ARRAY, node, mContext.getLocation(node),
                        "Use `new SparseBooleanArray(...)` instead for better performance");
            }
        }
    }

    private static String getUseValueOfErrorMessage(String typeName, String argument) {
        // Keep in sync with {@link #getReplacedType} below
        return String.format("Use `%1$s.valueOf(%2$s)` instead",
                typeName.substring(typeName.lastIndexOf('.') + 1), argument);
    }

    /**
     * For an error message for an {@link #USE_VALUE_OF} issue reported by this detector,
     * returns the type being replaced. Intended to use for IDE quickfix implementations.
     */
    @SuppressWarnings("unused") // Used by the IDE
    @Nullable
    public static String getReplacedType(@NonNull String message, @NonNull TextFormat format) {
        message = format.toText(message);
        int index = message.indexOf('.');
        if (index != -1 && message.startsWith("Use ")) {
            return message.substring(4, index);
        }
        return null;
    }

    /** Visitor which records variable names assigned into */
    private static class AssignmentTracker extends AbstractUastVisitor {
        private final Collection<String> mVariables;

        AssignmentTracker(Collection<String> variables) {
            mVariables = variables;
        }

        @Override
        public boolean visitBinaryExpression(UBinaryExpression node) {
            if (node.getOperator() instanceof UastBinaryOperator.AssignOperator) {
                UExpression left = node.getLeftOperand();
                if (left instanceof UQualifiedExpression) {
                    UQualifiedExpression ref = (UQualifiedExpression) left;
                    if (ref.getReceiver() instanceof UThisExpression ||
                            ref.getReceiver() instanceof USuperExpression) {
                        String identifier = ref.getIdentifier();
                        if (identifier != null) {
                            mVariables.add(identifier);
                        }
                    } else {
                        mVariables.add(ref.renderString());
                    }
                } else if (left instanceof USimpleReferenceExpression) {
                    mVariables.add(((USimpleReferenceExpression) left).getIdentifier());
                }
            }

            return super.visitBinaryExpression(node);
        }
    }
}
