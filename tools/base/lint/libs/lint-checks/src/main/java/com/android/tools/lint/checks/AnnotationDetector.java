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

import static com.android.SdkConstants.ATTR_VALUE;
import static com.android.SdkConstants.FQCN_SUPPRESS_LINT;
import static com.android.SdkConstants.INT_DEF_ANNOTATION;
import static com.android.SdkConstants.STRING_DEF_ANNOTATION;
import static com.android.SdkConstants.SUPPORT_ANNOTATIONS_PREFIX;
import static com.android.SdkConstants.TYPE_DEF_FLAG_ATTRIBUTE;
import static com.android.tools.lint.checks.PermissionRequirement.getAnnotationBooleanValue;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_ALL_OF;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_ANY_OF;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_FROM;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_MAX;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_MIN;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_MULTIPLE;
import static com.android.tools.lint.checks.SupportAnnotationDetector.ATTR_TO;
import static com.android.tools.lint.checks.SupportAnnotationDetector.CHECK_RESULT_ANNOTATION;
import static com.android.tools.lint.checks.SupportAnnotationDetector.COLOR_INT_ANNOTATION;
import static com.android.tools.lint.checks.SupportAnnotationDetector.FLOAT_RANGE_ANNOTATION;
import static com.android.tools.lint.checks.SupportAnnotationDetector.INT_RANGE_ANNOTATION;
import static com.android.tools.lint.checks.SupportAnnotationDetector.PERMISSION_ANNOTATION;
import static com.android.tools.lint.checks.SupportAnnotationDetector.PERMISSION_ANNOTATION_READ;
import static com.android.tools.lint.checks.SupportAnnotationDetector.PERMISSION_ANNOTATION_WRITE;
import static com.android.tools.lint.checks.SupportAnnotationDetector.RES_SUFFIX;
import static com.android.tools.lint.checks.SupportAnnotationDetector.SIZE_ANNOTATION;
import static com.android.tools.lint.checks.SupportAnnotationDetector.filterRelevantAnnotations;
import static com.android.tools.lint.checks.SupportAnnotationDetector.getDoubleAttribute;
import static com.android.tools.lint.checks.SupportAnnotationDetector.getLongAttribute;
import static com.android.tools.lint.client.api.JavaParser.TYPE_DOUBLE;
import static com.android.tools.lint.client.api.JavaParser.TYPE_FLOAT;
import static com.android.tools.lint.client.api.JavaParser.TYPE_INT;
import static com.android.tools.lint.client.api.JavaParser.TYPE_LONG;
import static com.android.tools.lint.client.api.JavaParser.TYPE_STRING;
import static com.android.tools.lint.client.api.UastLintUtils.findLastAssignment;
import static com.android.tools.lint.detector.api.LintUtils.findSubstring;
import static com.android.tools.lint.detector.api.LintUtils.getAutoBoxedType;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.ConstantEvaluator;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.TextFormat;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.jetbrains.uast.UAnnotated;
import org.jetbrains.uast.UAnnotation;
import org.jetbrains.uast.UArrayValue;
import org.jetbrains.uast.UBinaryExpressionWithType;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UConstantValue;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UDeclarationsExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UParenthesizedExpression;
import org.jetbrains.uast.UResolvedArrayType;
import org.jetbrains.uast.UResolvedType;
import org.jetbrains.uast.UStringValue;
import org.jetbrains.uast.USwitchExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastClassKind;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastFunctionKind;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.expressions.UReferenceExpression;
import org.jetbrains.uast.util.UastExpressionUtils;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks annotations to make sure they are valid
 */
public class AnnotationDetector extends Detector implements Detector.UastScanner {

    public static final Implementation IMPLEMENTATION = new Implementation(
              AnnotationDetector.class,
              Scope.JAVA_FILE_SCOPE);

    /** Placing SuppressLint on a local variable doesn't work for class-file based checks */
    public static final Issue INSIDE_METHOD = Issue.create(
            "LocalSuppress", //$NON-NLS-1$
            "@SuppressLint on invalid element",

            "The `@SuppressAnnotation` is used to suppress Lint warnings in Java files. However, " +
            "while many lint checks analyzes the Java source code, where they can find " +
            "annotations on (for example) local variables, some checks are analyzing the " +
            "`.class` files. And in class files, annotations only appear on classes, fields " +
            "and methods. Annotations placed on local variables disappear. If you attempt " +
            "to suppress a lint error for a class-file based lint check, the suppress " +
            "annotation not work. You must move the annotation out to the surrounding method.",

            Category.CORRECTNESS,
            3,
            Severity.ERROR,
            IMPLEMENTATION);

    /** Incorrectly using a support annotation */
    public static final Issue ANNOTATION_USAGE = Issue.create(
            "SupportAnnotationUsage", //$NON-NLS-1$
            "Incorrect support annotation usage",

            "This lint check makes sure that the support annotations (such as " +
            "`@IntDef` and `@ColorInt`) are used correctly. For example, it's an " +
            "error to specify an `@IntRange` where the `from` value is higher than " +
            "the `to` value.",

            Category.CORRECTNESS,
            2,
            Severity.ERROR,
            IMPLEMENTATION);

    /** IntDef annotations should be unique */
    public static final Issue UNIQUE = Issue.create(
            "UniqueConstants", //$NON-NLS-1$
            "Overlapping Enumeration Constants",

            "The `@IntDef` annotation allows you to " +
            "create a light-weight \"enum\" or type definition. However, it's possible to " +
            "accidentally specify the same value for two or more of the values, which can " +
            "lead to hard-to-detect bugs. This check looks for this scenario and flags any " +
            "repeated constants.\n" +
            "\n" +
            "In some cases, the repeated constant is intentional (for example, renaming a " +
            "constant to a more intuitive name, and leaving the old name in place for " +
            "compatibility purposes.)  In that case, simply suppress this check by adding a " +
            "`@SuppressLint(\"UniqueConstants\")` annotation.",

            Category.CORRECTNESS,
            3,
            Severity.ERROR,
            IMPLEMENTATION);

    /** Flags should typically be specified as bit shifts */
    public static final Issue FLAG_STYLE = Issue.create(
            "ShiftFlags", //$NON-NLS-1$
            "Dangerous Flag Constant Declaration",

            "When defining multiple constants for use in flags, the recommended style is " +
            "to use the form `1 << 2`, `1 << 3`, `1 << 4` and so on to ensure that the " +
            "constants are unique and non-overlapping.",

            Category.CORRECTNESS,
            3,
            Severity.WARNING,
            IMPLEMENTATION);

    /** All IntDef constants should be included in switch */
    public static final Issue SWITCH_TYPE_DEF = Issue.create(
            "SwitchIntDef", //$NON-NLS-1$
            "Missing @IntDef in Switch",

            "This check warns if a `switch` statement does not explicitly include all " +
            "the values declared by the typedef `@IntDef` declaration.",

            Category.CORRECTNESS,
            3,
            Severity.WARNING,
            IMPLEMENTATION);

    /** Constructs a new {@link AnnotationDetector} check */
    public AnnotationDetector() {
    }

    // ---- Implements UastScanner ----

    /**
     * Set of fields we've already warned about {@link #FLAG_STYLE} for; these can
     * be referenced multiple times, so we should only flag them once
     */
    private Set<UElement> mWarnedFlags;

    @Nullable
    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        List<Class<? extends UElement>> types = new ArrayList<Class<? extends UElement>>(2);
        types.add(UAnnotation.class);
        types.add(USwitchExpression.class);
        return types;
    }

    @Nullable
    @Override
    public UastVisitor createUastVisitor(@NonNull JavaContext context) {
        return new AnnotationChecker(context);
    }

    private class AnnotationChecker extends AbstractUastVisitor {
        private final JavaContext mContext;

        public AnnotationChecker(JavaContext context) {
            mContext = context;
        }

        @Override
        public boolean visitAnnotation(UAnnotation annotation) {
            boolean ret = super.visitAnnotation(annotation);

            String type = annotation.getFqName();
            if (type == null || type.startsWith("java.lang.")) {
                return ret;
            }

            if (FQCN_SUPPRESS_LINT.equals(type)) {
                UAnnotated owner = UastUtils.getParentOfType(annotation, UAnnotated.class);
                // Only flag local variables and parameters (not classes, fields and methods)
                if (!(owner instanceof UVariable)) {
                    return ret;
                }

                Collection<UConstantValue<?>> attributes = annotation.getValues().values();
                if (attributes.size() == 1) {
                    UConstantValue<?> value = attributes.iterator().next();
                    if (value instanceof UStringValue) {
                        Object v = value.getValue();
                        String id = (String) v;
                        checkSuppressLint(annotation, id);
                    } else if (value instanceof UArrayValue) {
                        UArrayValue initializer = (UArrayValue) value;
                        for (UConstantValue<?> expression : initializer.getValue()) {
                            if (expression instanceof UStringValue) {
                                Object v = expression.getValue();
                                String id = (String) v;
                                if (!checkSuppressLint(annotation, id)) {
                                    return ret;
                                }
                            }
                        }
                    }
                }
            } else if (type.startsWith(SUPPORT_ANNOTATIONS_PREFIX)) {
                if (CHECK_RESULT_ANNOTATION.equals(type)) {
                    // Check that the return type of this method is not void!
                    if (annotation.getParent() instanceof UFunction) {
                        UFunction method = (UFunction) annotation.getParent();
                        UType returnType = method.getReturnType();
                        if (method.getKind() != UastFunctionKind.CONSTRUCTOR
                                && returnType != null && returnType.isVoid()) {
                            mContext.report(ANNOTATION_USAGE, annotation,
                                    mContext.getLocation(annotation),
                                    "@CheckResult should not be specified on `void` methods");
                        }
                    }
                } else if (INT_RANGE_ANNOTATION.equals(type)
                        || FLOAT_RANGE_ANNOTATION.equals(type)) {
                    // Check that the annotated element's type is int or long.
                    // Also make sure that from <= to.
                    boolean invalid;
                    if (INT_RANGE_ANNOTATION.equals(type)) {
                        checkTargetType(annotation, TYPE_INT, TYPE_LONG, true);

                        long from = getLongAttribute(annotation, ATTR_FROM, Long.MIN_VALUE);
                        long to = getLongAttribute(annotation, ATTR_TO, Long.MAX_VALUE);
                        invalid = from > to;
                    } else {
                        checkTargetType(annotation, TYPE_FLOAT, TYPE_DOUBLE, true);

                        double from = getDoubleAttribute(annotation, ATTR_FROM,
                                Double.NEGATIVE_INFINITY);
                        double to = getDoubleAttribute(annotation, ATTR_TO,
                                Double.POSITIVE_INFINITY);
                        invalid = from > to;
                    }
                    if (invalid) {
                        mContext.report(ANNOTATION_USAGE, annotation, mContext.getLocation(annotation),
                                "Invalid range: the `from` attribute must be less than "
                                        + "the `to` attribute");
                    }
                } else if (SIZE_ANNOTATION.equals(type)) {
                    // Check that the annotated element's type is an array, or a collection
                    // (or at least not an int or long; if so, suggest IntRange)
                    // Make sure the size and the modulo is not negative.
                    int unset = -42;
                    long exact = getLongAttribute(annotation, ATTR_VALUE, unset);
                    long min = getLongAttribute(annotation, ATTR_MIN, Long.MIN_VALUE);
                    long max = getLongAttribute(annotation, ATTR_MAX, Long.MAX_VALUE);
                    long multiple = getLongAttribute(annotation, ATTR_MULTIPLE, 1);
                    if (min > max) {
                        mContext.report(ANNOTATION_USAGE, annotation, mContext.getLocation(annotation),
                                "Invalid size range: the `min` attribute must be less than "
                                        + "the `max` attribute");
                    } else if (multiple < 1) {
                        mContext.report(ANNOTATION_USAGE, annotation, mContext.getLocation(annotation),
                                "The size multiple must be at least 1");

                    } else if (exact < 0 && exact != unset) {
                        mContext.report(ANNOTATION_USAGE, annotation, mContext.getLocation(annotation),
                                "The size can't be negative");
                    }
                } else if (COLOR_INT_ANNOTATION.equals(type)) {
                    // Check that ColorInt applies to the right type
                    checkTargetType(annotation, TYPE_INT, TYPE_LONG, true);
                } else if (INT_DEF_ANNOTATION.equals(type)) {
                    // Make sure IntDef constants are unique
                    ensureUniqueValues(annotation);
                } else if (PERMISSION_ANNOTATION.equals(type) ||
                        PERMISSION_ANNOTATION_READ.equals(type) ||
                        PERMISSION_ANNOTATION_WRITE.equals(type)) {
                    // Check that if there are no arguments, this is specified on a parameter,
                    // and conversely, on methods and fields there is a valid argument.
                    if (annotation.getParent() instanceof UFunction) {
                        String value = PermissionRequirement.getAnnotationStringValue(annotation, ATTR_VALUE);
                        String[] anyOf = PermissionRequirement.getAnnotationStringValues(annotation, ATTR_ANY_OF);
                        String[] allOf = PermissionRequirement.getAnnotationStringValues(annotation, ATTR_ALL_OF);

                        int set = 0;
                        //noinspection VariableNotUsedInsideIf
                        if (value != null) {
                            set++;
                        }
                        //noinspection VariableNotUsedInsideIf
                        if (allOf != null) {
                            set++;
                        }
                        //noinspection VariableNotUsedInsideIf
                        if (anyOf != null) {
                            set++;
                        }

                        if (set == 0) {
                            mContext.report(ANNOTATION_USAGE, annotation,
                                    mContext.getLocation(annotation),
                                    "For methods, permission annotation should specify one "
                                            + "of `value`, `anyOf` or `allOf`");
                        } else if (set > 1) {
                            mContext.report(ANNOTATION_USAGE, annotation,
                                    mContext.getLocation(annotation),
                                    "Only specify one of `value`, `anyOf` or `allOf`");
                        }
                    }

                } else if (type.endsWith(RES_SUFFIX)) {
                    // Check that resource type annotations are on ints
                    checkTargetType(annotation, TYPE_INT, TYPE_LONG, true);
                }
            } else {
                // Look for typedefs (and make sure they're specified on the right type)
                UClass cls = annotation.resolve(mContext);
                if (cls != null) {
                    if (cls.getKind() == UastClassKind.ANNOTATION) {
                        for (UAnnotation ann : mContext.getAnnotationsWithExternal(cls)) {
                            if (ann.matchesName(INT_DEF_ANNOTATION)) {
                                checkTargetType(annotation, TYPE_INT, TYPE_LONG, true);
                            } else if (STRING_DEF_ANNOTATION.equals(type)) {
                                checkTargetType(annotation, TYPE_STRING, null, true);
                            }
                        }
                    }
                }
            }

            return super.visitAnnotation(annotation);
        }

        private void checkTargetType(@NonNull UAnnotation node, @NonNull String type1,
                @Nullable String type2, boolean allowCollection) {
            UAnnotated owner = UastUtils.getParentOfType(node, UAnnotated.class);
            if (owner != null) {
                UElement parent = owner.getParent();
                UType type;
                if (parent instanceof UDeclarationsExpression) {
                    List<UElement> elements = ((UDeclarationsExpression) parent).getDeclarations();
                    if (!elements.isEmpty()) {
                        UElement element = elements.get(0);
                        if (element instanceof UVariable) {
                            type = ((UVariable) element).getType();
                        } else {
                            return;
                        }
                    } else {
                        return;
                    }
                } else if (parent instanceof UFunction) {
                    UFunction method = (UFunction) parent;
                    type = method.getKind() == UastFunctionKind.CONSTRUCTOR
                            ? UastUtils.getContainingClassOrEmpty(method).getDefaultType()
                            : method.getReturnType();
                } else if (parent instanceof UVariable) {
                    // Field or local variable or parameter
                    type = ((UVariable) parent).getType();
                } else {
                    return;
                }
                if (type == null) {
                    return;
                }

                if (allowCollection) {
                    UResolvedType resolvedType = type.resolve();
                    if (type instanceof UResolvedArrayType) {
                        // For example, int[]
                        type = ((UResolvedArrayType) resolvedType).findDeepElementType().getType();
                    } else {
                        // For example, List<Integer>
                        if (type.getArguments().size() == 1) {
                            UClass resolved = type.resolveToClass(mContext);
                            if (resolved != null &&
                                    resolved.isSubclassOf("java.util.Collection", false)) {
                                type = type.getArguments().get(0).getType();
                            }
                        }
                    }
                }

                String typeName = type.getFqName();
                if (typeName == null) {
                    return;
                }

                if (!typeName.equals(type1)
                        && (type2 == null || !typeName.equals(type2))) {
                    // Autoboxing? You can put @DrawableRes on a java.lang.Integer for example
                    if (typeName.equals(getAutoBoxedType(type1))
                          || type2 != null && typeName.equals(getAutoBoxedType(type2))) {
                        return;
                    }

                    String expectedTypes = type2 == null ? type1 : type1 + " or " + type2;
                    if (typeName.equals(TYPE_STRING)) {
                        typeName = "String";
                    }
                    String message = String.format(
                            "This annotation does not apply for type %1$s; expected %2$s",
                            typeName, expectedTypes);
                    Location location = mContext.getLocation(node);
                    mContext.report(ANNOTATION_USAGE, node, location, message);
                }
            }
        }

        @Override
        public boolean visitSwitchExpression(USwitchExpression node) {
            UExpression condition = node.getExpression();
            UType conditionType = condition != null ? condition.getExpressionType() : null;
            if (conditionType != null && conditionType.isInt()) {
                UAnnotation annotation = findIntDef(condition);
                if (annotation != null) {
                    checkSwitch(node, annotation);
                }
            }

            return super.visitSwitchExpression(node);
        }

        /**
         * Searches for the corresponding @IntDef annotation definition associated
         * with a given node
         */
        @Nullable
        private UAnnotation findIntDef(@NonNull UElement node) {
            if (node instanceof UReferenceExpression) {
                UDeclaration resolved = ((UReferenceExpression) node).resolve(mContext);
                if (resolved instanceof UAnnotated) {
                    List<UAnnotation> annotations = UastUtils.getAllAnnotations(resolved, mContext);
                    UAnnotation annotation = SupportAnnotationDetector.findIntDef(
                            filterRelevantAnnotations(annotations, mContext));
                    if (annotation != null) {
                        return annotation;
                    }
                }

                if (resolved instanceof UVariable) {
                    UVariable variable = (UVariable) resolved;
                    UElement lastAssignment = findLastAssignment(variable, node, mContext);
                    if (lastAssignment != null) {
                        return findIntDef(lastAssignment);
                    }

                }
            } else if (node instanceof UCallExpression) {
                UFunction method = ((UCallExpression) node).resolve(mContext);
                if (method != null) {
                    List<UAnnotation> annotations = UastUtils.getAllAnnotations(method, mContext);
                    UAnnotation annotation = SupportAnnotationDetector.findIntDef(
                            filterRelevantAnnotations(annotations, mContext));
                    if (annotation != null) {
                        return annotation;
                    }
                }
            } else if (node instanceof UIfExpression) {
                UIfExpression expression = (UIfExpression) node;
                if (expression.getThenBranch() != null) {
                    UAnnotation result = findIntDef(expression.getThenBranch());
                    if (result != null) {
                        return result;
                    }
                }
                if (expression.getElseBranch() != null) {
                    UAnnotation result = findIntDef(expression.getElseBranch());
                    if (result != null) {
                        return result;
                    }
                }
            } else if (UastExpressionUtils.isTypeCast(node)) {
                UBinaryExpressionWithType cast = (UBinaryExpressionWithType) node;
                return findIntDef(cast.getOperand());
            } else if (node instanceof UParenthesizedExpression) {
                UParenthesizedExpression expression = (UParenthesizedExpression) node;
                if (expression.getExpressionType() != null) {
                    return findIntDef(expression.getExpression());
                }
            }

            return null;
        }

        private void checkSwitch(@NonNull USwitchExpression node, @NonNull UAnnotation annotation) {
            UExpression block = node.getBody();
            if (block == null) {
                return;
            }

            UConstantValue<?> value = annotation.getValue(ATTR_VALUE);
            if (value == null) {
                value = annotation.getValue(null);
            }
            if (value == null) {
                return;
            }

            if (!(value instanceof UArrayValue)) {
                return;
            }

            UArrayValue array = (UArrayValue)value;
            List<UConstantValue<?>> allowedValues = array.getValue();

            List<UElement> fields = Lists.newArrayListWithCapacity(allowedValues.size());
            for (UConstantValue<?> allowedValue : allowedValues) {
                UElement original = allowedValue.getOriginal();
                if (allowedValue instanceof UReferenceExpression) {
                    UElement resolved = ((UReferenceExpression) allowedValue).resolve(mContext);
                    if (resolved != null) {
                        fields.add(resolved);
                    }
                } else if (allowedValue instanceof ULiteralExpression) {
                    fields.add(original);
                }
            }


            // Empty switch: arguably we could skip these (since the IDE already warns about
            // empty switches) but it's useful since the quickfix will kick in and offer all
            // the missing ones when you're editing.
            //   if (block.getStatements().length == 0) { return; }

            //for (PsiStatement statement : block.getStatements()) {
            //    if (statement instanceof PsiSwitchLabelStatement) {
            //        PsiSwitchLabelStatement caseStatement = (PsiSwitchLabelStatement) statement;
            //        PsiExpression expression = caseStatement.getCaseValue();
            //        if (expression instanceof PsiLiteral) {
            //            // Report warnings if you specify hardcoded constants.
            //            // It's the wrong thing to do.
            //            List<String> list = computeFieldNames(node, Arrays.asList(allowedValues),
            //                    context);
            //            // Keep error message in sync with {@link #getMissingCases}
            //            String message = "Don't use a constant here; expected one of: " + Joiner
            //                    .on(", ").join(list);
            //            mContext.report(SWITCH_TYPE_DEF, expression,
            //                    mContext.getLocation(expression), message);
            //            return; // Don't look for other missing typedef constants since you might
            //            // have aliased with value
            //        } else if (expression instanceof PsiReferenceExpression) { // default case can have null expression
            //            PsiElement resolved = ((PsiReferenceExpression) expression).resolve();
            //            if (resolved == null) {
            //                // If there are compilation issues (e.g. user is editing code) we
            //                // can't be certain, so don't flag anything.
            //                return;
            //            }
            //            if (resolved instanceof PsiField) {
            //                // We can't just do
            //                //    fields.remove(resolved);
            //                // since the fields list contains instances of potentially
            //                // different types with different hash codes (due to the
            //                // external annotations, which are not of the same type as
            //                // for example the ECJ based ones.
            //                //
            //                // The equals method on external field class deliberately handles
            //                // this (but it can't make its hash code match what
            //                // the ECJ fields do, which is tied to the ECJ binding hash code.)
            //                // So instead, manually check for equals. These lists tend to
            //                // be very short anyway.
            //                boolean found = false;
            //                ListIterator<PsiElement> iterator = fields.listIterator();
            //                while (iterator.hasNext()) {
            //                    PsiElement field = iterator.next();
            //                    if (field.equals(resolved)) {
            //                        iterator.remove();
            //                        found = true;
            //                        break;
            //                    }
            //                }
            //                if (!found) {
            //                    // Look for local alias
            //                    PsiExpression initializer = ((PsiField) resolved).getInitializer();
            //                    if (initializer instanceof PsiReferenceExpression) {
            //                        resolved = ((PsiReferenceExpression) expression).resolve();
            //                        if (resolved instanceof PsiField) {
            //                            iterator = fields.listIterator();
            //                            while (iterator.hasNext()) {
            //                                PsiElement field = iterator.next();
            //                                if (field.equals(initializer)) {
            //                                    iterator.remove();
            //                                    found = true;
            //                                    break;
            //                                }
            //                            }
            //                        }
            //                    }
            //                }
            //
            //                if (!found) {
            //                    List<String> list = computeFieldNames(node, Arrays.asList(allowedValues),
            //                            context);
            //                    // Keep error message in sync with {@link #getMissingCases}
            //                    String message = "Unexpected constant; expected one of: " + Joiner
            //                            .on(", ").join(list);
            //                    Location location = mContext.getNameLocation(expression);
            //                    mContext.report(SWITCH_TYPE_DEF, expression, location, message);
            //                }
            //            }
            //        }
            //    }
            //}
            //if (!fields.isEmpty()) {
            //    List<String> list = computeFieldNames(node, fields, mContext);
            //    // Keep error message in sync with {@link #getMissingCases}
            //    String message = "Switch statement on an `int` with known associated constant "
            //            + "missing case " + Joiner.on(", ").join(list);
            //    Location location = mContext.getLocation(node.getExpression());
            //    mContext.report(SWITCH_TYPE_DEF, node, location, message);
            //}
        }

        private void ensureUniqueValues(@NonNull UAnnotation node) {
            UConstantValue<?> value = node.getValue(ATTR_VALUE);
            if (value == null) {
                value = node.getValue(null);
            }
            if (value == null) {
                return;
            }

            if (!(value instanceof UArrayValue)) {
                return;
            }

            UArrayValue array = (UArrayValue) value;
            List<UConstantValue<?>> initializers = array.getValue();
            Map<Number,Integer> valueToIndex = Maps.newHashMapWithExpectedSize(initializers.size());

            boolean flag = getAnnotationBooleanValue(node, TYPE_DEF_FLAG_ATTRIBUTE) == Boolean.TRUE;
            if (flag) {
                ensureUsingFlagStyle(initializers);
            }

            ConstantEvaluator constantEvaluator = new ConstantEvaluator(mContext);
            for (int index = 0; index < initializers.size(); index++) {
                UConstantValue<?> expression = initializers.get(index);

                Object o = constantEvaluator.evaluate(expression);

                if (o instanceof Number) {
                    Number number = (Number) o;
                    if (valueToIndex.containsKey(number)) {
                        @SuppressWarnings("UnnecessaryLocalVariable")
                        Number repeatedValue = number;

                        Location location;
                        String message;
                        int prevIndex = valueToIndex.get(number);
                        UConstantValue<?> prevConstant = initializers.get(prevIndex);
                        message = String.format(
                                "Constants `%1$s` and `%2$s` specify the same exact "
                                        + "value (%3$s); this is usually a cut & paste or "
                                        + "merge error",
                                getOriginalOrConstant(expression),
                                getOriginalOrConstant(prevConstant),
                                repeatedValue.toString());
                        location = mContext.getLocation(expression);
                        Location secondary = mContext.getLocation(prevConstant);
                        secondary.setMessage("Previous same value");
                        location.setSecondary(secondary);
                        UElement scope = getAnnotationScope(node);
                        mContext.report(UNIQUE, scope, location, message);
                        break;
                    }
                    valueToIndex.put(number, index);
                }
            }
        }

        private String getOriginalOrConstant(UConstantValue<?> constantValue) {
            UExpression original = constantValue.getOriginal();
            if (original != null) {
                return original.originalString();
            } else {
                return constantValue.getValue().toString();
            }
        }

        private void ensureUsingFlagStyle(@NonNull List<UConstantValue<?>> constants) {
            if (constants.size() < 3) {
                return;
            }

            for (UConstantValue<?> constant : constants) {
                if (constant instanceof UReferenceExpression) {
                    UElement resolved = ((UReferenceExpression) constant).resolve(mContext);
                    if (resolved instanceof UVariable) {
                        UExpression initializer = ((UVariable) resolved).getInitializer();
                        if (initializer instanceof ULiteralExpression) {
                            ULiteralExpression literal = (ULiteralExpression) initializer;
                            Object o = literal.getValue();
                            if (!(o instanceof Number)) {
                                continue;
                            }
                            long value = ((Number)o).longValue();
                            // Allow -1, 0 and 1. You can write 1 as "1 << 0" but IntelliJ for
                            // example warns that that's a redundant shift.
                            if (Math.abs(value) <= 1) {
                                continue;
                            }
                            // Only warn if we're setting a specific bit
                            if (Long.bitCount(value) != 1) {
                                continue;
                            }
                            int shift = Long.numberOfTrailingZeros(value);
                            if (mWarnedFlags == null) {
                                mWarnedFlags = Sets.newHashSet();
                            }
                            if (!mWarnedFlags.add(resolved)) {
                                return;
                            }
                            String message = String.format(
                                    "Consider declaring this constant using 1 << %1$d instead",
                                    shift);
                            Location location = mContext.getLocation(initializer);
                            mContext.report(FLAG_STYLE, initializer, location, message);
                        }
                    }
                }
            }
        }

        private boolean checkSuppressLint(@NonNull UAnnotation node, @NonNull String id) {
            IssueRegistry registry = mContext.getDriver().getRegistry();
            Issue issue = registry.getIssue(id);
            // Special-case the ApiDetector issue, since it does both source file analysis
            // only on field references, and class file analysis on the rest, so we allow
            // annotations outside of methods only on fields
            if (issue != null && !issue.getImplementation().getScope().contains(Scope.JAVA_FILE)
                    || issue == ApiDetector.UNSUPPORTED) {
                // This issue doesn't have AST access: annotations are not
                // available for local variables or parameters
                UElement scope = getAnnotationScope(node);
                mContext.report(INSIDE_METHOD, scope, mContext.getLocation(node), String.format(
                    "The `@SuppressLint` annotation cannot be used on a local " +
                    "variable with the lint check '%1$s': move out to the " +
                    "surrounding method", id));
                return false;
            }

            return true;
        }
    }

    @NonNull
    private static List<String> computeFieldNames(@NonNull USwitchExpression node,
            Iterable<?> allowedValues, UastContext context) {
        List<String> list = Lists.newArrayList();
        for (Object o : allowedValues) {
            if (o instanceof UReferenceExpression) {
                UDeclaration resolved = ((UReferenceExpression) o).resolve(context);
                if (resolved != null) {
                    o = resolved;
                }
            } else if (o instanceof ULiteralExpression) {
                list.add("`" + ((ULiteralExpression) o).getValue() + '`');
                continue;
            }

            if (o instanceof UVariable) {
                UVariable field = (UVariable) o;
                // Only include class name if necessary
                String name = field.getName();
                UClass clz = UastUtils.getContainingClass(node);
                if (clz != null) {
                    name = clz.getName() + '.' + field.getName();
                }
                list.add('`' + name + '`');
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Given an error message produced by this lint detector for the {@link #SWITCH_TYPE_DEF} issue
     * type, returns the list of missing enum cases. <p> Intended for IDE quickfix implementations.
     *
     * @param errorMessage the error message associated with the error
     * @param format       the format of the error message
     * @return the list of enum cases, or null if not recognized
     */
    @Nullable
    static List<String> getMissingCases(@NonNull String errorMessage,
            @NonNull TextFormat format) {
        errorMessage = format.toText(errorMessage);

        String substring = findSubstring(errorMessage, " missing case ", null);
        if (substring == null) {
            substring = findSubstring(errorMessage, "expected one of: ", null);
        }
        if (substring != null) {
            return Splitter.on(",").trimResults().splitToList(substring);
        }

        return null;
    }

    /**
     * Returns the node to use as the scope for the given annotation node.
     * You can't annotate an annotation itself (with {@code @SuppressLint}), but
     * you should be able to place an annotation next to it, as a sibling, to only
     * suppress the error on this annotated element, not the whole surrounding class.
     */
    @NonNull
    private static UElement getAnnotationScope(@NonNull UAnnotation node) {
        UElement scope = UastUtils.getParentOfType(node, UAnnotation.class, true);
        if (scope == null) {
            scope = node;
        }
        return scope;
    }
}
