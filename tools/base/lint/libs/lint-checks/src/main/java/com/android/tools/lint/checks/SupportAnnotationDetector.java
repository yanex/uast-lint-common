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

import static com.android.SdkConstants.ANDROID_URI;
import static com.android.SdkConstants.ATTR_NAME;
import static com.android.SdkConstants.ATTR_VALUE;
import static com.android.SdkConstants.CLASS_INTENT;
import static com.android.SdkConstants.INT_DEF_ANNOTATION;
import static com.android.SdkConstants.STRING_DEF_ANNOTATION;
import static com.android.SdkConstants.SUPPORT_ANNOTATIONS_PREFIX;
import static com.android.SdkConstants.TAG_PERMISSION;
import static com.android.SdkConstants.TAG_USES_PERMISSION;
import static com.android.SdkConstants.TAG_USES_PERMISSION_SDK_23;
import static com.android.SdkConstants.TAG_USES_PERMISSION_SDK_M;
import static com.android.SdkConstants.TYPE_DEF_FLAG_ATTRIBUTE;
import static com.android.resources.ResourceType.COLOR;
import static com.android.resources.ResourceType.DRAWABLE;
import static com.android.resources.ResourceType.MIPMAP;
import static com.android.tools.lint.checks.PermissionFinder.Operation.ACTION;
import static com.android.tools.lint.checks.PermissionFinder.Operation.READ;
import static com.android.tools.lint.checks.PermissionFinder.Operation.WRITE;
import static com.android.tools.lint.checks.PermissionRequirement.ATTR_PROTECTION_LEVEL;
import static com.android.tools.lint.checks.PermissionRequirement.VALUE_DANGEROUS;
import static com.android.tools.lint.checks.PermissionRequirement.getAnnotationBooleanValue;
import static com.android.tools.lint.checks.PermissionRequirement.getAnnotationDoubleValue;
import static com.android.tools.lint.checks.PermissionRequirement.getAnnotationLongValue;
import static com.android.tools.lint.checks.PermissionRequirement.getAnnotationStringValue;
import static com.android.tools.lint.detector.api.LintUtils.skipParentheses;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.resources.ResourceType;
import com.android.sdklib.AndroidVersion;
import com.android.tools.lint.checks.PermissionFinder.Operation;
import com.android.tools.lint.checks.PermissionFinder.Result;
import com.android.tools.lint.checks.PermissionHolder.SetPermissionLookup;
import com.android.tools.lint.client.api.JavaEvaluator;
import com.android.tools.lint.client.api.LintClient;
import com.android.tools.lint.client.api.UastLintUtils;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.ConstantEvaluator;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Project;
import com.android.tools.lint.detector.api.ResourceEvaluator;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.TextFormat;
import com.android.utils.XmlUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import com.intellij.mock.MockProject;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClassType;

import org.jetbrains.uast.UAnnotation;
import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UBinaryExpressionWithType;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UCatchClause;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UExpressionValue;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UParenthesizedExpression;
import org.jetbrains.uast.UPrefixExpression;
import org.jetbrains.uast.UResolvedArrayType;
import org.jetbrains.uast.UResolvedType;
import org.jetbrains.uast.UResolvedTypeParameter;
import org.jetbrains.uast.UTryExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastBinaryOperator;
import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastFunctionKind;
import org.jetbrains.uast.UastLiteralUtils;
import org.jetbrains.uast.UastModifier;
import org.jetbrains.uast.UastPrefixOperator;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.UastVariableKind;
import org.jetbrains.uast.UArrayValue;
import org.jetbrains.uast.UConstantValue;
import org.jetbrains.uast.USimpleConstantValue;
import org.jetbrains.uast.UTypeValue;
import org.jetbrains.uast.expressions.UReferenceExpression;
import org.jetbrains.uast.java.JavaConverter;
import org.jetbrains.uast.java.JavaUCallExpression;
import org.jetbrains.uast.java.JavaUClass;
import org.jetbrains.uast.java.JavaUFunction;
import org.jetbrains.uast.java.JavaUType;
import org.jetbrains.uast.util.UastExpressionUtils;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Looks up annotations on method calls and enforces the various things they
 * express, e.g. for {@code @CheckReturn} it makes sure the return value is used,
 * for {@code ColorInt} it ensures that a proper color integer is passed in, etc.
 *
 * TODO: Throw in some annotation usage checks here too; e.g. specifying @Size without parameters,
 * specifying toInclusive without setting to, combining @ColorInt with any @ResourceTypeRes,
 * using @CheckResult on a void method, etc.
 */
public class SupportAnnotationDetector extends Detector implements Detector.UastScanner {

    public static final Implementation IMPLEMENTATION
            = new Implementation(SupportAnnotationDetector.class, Scope.JAVA_FILE_SCOPE);

    /** Method result should be used */
    public static final Issue RANGE = Issue.create(
        "Range", //$NON-NLS-1$
        "Outside Range",

        "Some parameters are required to in a particular numerical range; this check " +
        "makes sure that arguments passed fall within the range. For arrays, Strings " +
        "and collections this refers to the size or length.",

        Category.CORRECTNESS,
        6,
        Severity.ERROR,
        IMPLEMENTATION);

    /**
     * Attempting to set a resource id as a color
     */
    public static final Issue RESOURCE_TYPE = Issue.create(
        "ResourceType", //$NON-NLS-1$
        "Wrong Resource Type",

        "Ensures that resource id's passed to APIs are of the right type; for example, " +
        "calling `Resources.getColor(R.string.name)` is wrong.",

        Category.CORRECTNESS,
        7,
        Severity.FATAL,
        IMPLEMENTATION);

    /** Attempting to set a resource id as a color */
    public static final Issue COLOR_USAGE = Issue.create(
        "ResourceAsColor", //$NON-NLS-1$
        "Should pass resolved color instead of resource id",

        "Methods that take a color in the form of an integer should be passed " +
        "an RGB triple, not the actual color resource id. You must call " +
        "`getResources().getColor(resource)` to resolve the actual color value first.",

        Category.CORRECTNESS,
        7,
        Severity.ERROR,
        IMPLEMENTATION);

    /** Passing the wrong constant to an int or String method */
    public static final Issue TYPE_DEF = Issue.create(
        "WrongConstant", //$NON-NLS-1$
        "Incorrect constant",

        "Ensures that when parameter in a method only allows a specific set " +
        "of constants, calls obey those rules.",

        Category.SECURITY,
        6,
        Severity.ERROR,
        IMPLEMENTATION);

    /** Method result should be used */
    public static final Issue CHECK_RESULT = Issue.create(
        "CheckResult", //$NON-NLS-1$
        "Ignoring results",

        "Some methods have no side effects, an calling them without doing something " +
        "without the result is suspicious. ",

        Category.CORRECTNESS,
        6,
        Severity.WARNING,
            IMPLEMENTATION);

    /** Failing to enforce security by just calling check permission */
    public static final Issue CHECK_PERMISSION = Issue.create(
        "UseCheckPermission", //$NON-NLS-1$
        "Using the result of check permission calls",

        "You normally want to use the result of checking a permission; these methods " +
        "return whether the permission is held; they do not throw an error if the permission " +
        "is not granted. Code which does not do anything with the return value probably " +
        "meant to be calling the enforce methods instead, e.g. rather than " +
        "`Context#checkCallingPermission` it should call `Context#enforceCallingPermission`.",

        Category.SECURITY,
        6,
        Severity.WARNING,
        IMPLEMENTATION);

    /** Method result should be used */
    public static final Issue MISSING_PERMISSION = Issue.create(
            "MissingPermission", //$NON-NLS-1$
            "Missing Permissions",

            "This check scans through your code and libraries and looks at the APIs being used, " +
            "and checks this against the set of permissions required to access those APIs. If " +
            "the code using those APIs is called at runtime, then the program will crash.\n" +
            "\n" +
            "Furthermore, for permissions that are revocable (with targetSdkVersion 23), client " +
            "code must also be prepared to handle the calls throwing an exception if the user " +
            "rejects the request for permission at runtime.",

            Category.CORRECTNESS,
            9,
            Severity.ERROR,
            IMPLEMENTATION);

    /** Passing the wrong constant to an int or String method */
    public static final Issue THREAD = Issue.create(
            "WrongThread", //$NON-NLS-1$
            "Wrong Thread",

            "Ensures that a method which expects to be called on a specific thread, is actually " +
            "called from that thread. For example, calls on methods in widgets should always " +
            "be made on the UI thread.",

            Category.CORRECTNESS,
            6,
            Severity.ERROR,
            IMPLEMENTATION)
            .addMoreInfo(
                    "http://developer.android.com/guide/components/processes-and-threads.html#Threads");

    public static final String CHECK_RESULT_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "CheckResult"; //$NON-NLS-1$
    public static final String COLOR_INT_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "ColorInt"; //$NON-NLS-1$
    public static final String INT_RANGE_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "IntRange"; //$NON-NLS-1$
    public static final String FLOAT_RANGE_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "FloatRange"; //$NON-NLS-1$
    public static final String SIZE_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "Size"; //$NON-NLS-1$
    public static final String PERMISSION_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "RequiresPermission"; //$NON-NLS-1$
    public static final String UI_THREAD_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "UiThread"; //$NON-NLS-1$
    public static final String MAIN_THREAD_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "MainThread"; //$NON-NLS-1$
    public static final String WORKER_THREAD_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "WorkerThread"; //$NON-NLS-1$
    public static final String BINDER_THREAD_ANNOTATION = SUPPORT_ANNOTATIONS_PREFIX + "BinderThread"; //$NON-NLS-1$
    public static final String PERMISSION_ANNOTATION_READ = PERMISSION_ANNOTATION + ".Read"; //$NON-NLS-1$
    public static final String PERMISSION_ANNOTATION_WRITE = PERMISSION_ANNOTATION + ".Write"; //$NON-NLS-1$

    public static final String RES_SUFFIX = "Res";
    public static final String THREAD_SUFFIX = "Thread";
    public static final String ATTR_SUGGEST = "suggest";
    public static final String ATTR_TO = "to";
    public static final String ATTR_FROM = "from";
    public static final String ATTR_FROM_INCLUSIVE = "fromInclusive";
    public static final String ATTR_TO_INCLUSIVE = "toInclusive";
    public static final String ATTR_MULTIPLE = "multiple";
    public static final String ATTR_MIN = "min";
    public static final String ATTR_MAX = "max";
    public static final String ATTR_ALL_OF = "allOf";
    public static final String ATTR_ANY_OF = "anyOf";
    public static final String ATTR_CONDITIONAL = "conditional";

    /**
     * Constructs a new {@link SupportAnnotationDetector} check
     */
    public SupportAnnotationDetector() {
    }

    private void checkMethodAnnotation(
            @NonNull JavaContext context,
            @NonNull UFunction method,
            @NonNull UElement call,
            @NonNull UAnnotation annotation) {
        String signature = annotation.getFqName();
        if (signature == null) {
            return;
        }
        if (CHECK_RESULT_ANNOTATION.equals(signature)
                // support findbugs annotation too
                || signature.endsWith(".CheckReturnValue")) {
            checkResult(context, call, annotation);
        } else if (signature.equals(PERMISSION_ANNOTATION)) {
            PermissionRequirement requirement = PermissionRequirement.create(context, annotation);
            checkPermission(context, call, method, null, requirement);
        } else if (signature.endsWith(THREAD_SUFFIX)
                && signature.startsWith(SUPPORT_ANNOTATIONS_PREFIX)) {
            checkThreading(context, call, method, signature);
        }
    }

    private void checkParameterAnnotations(
            @NonNull JavaContext context,
            @NonNull UExpression argument,
            @NonNull UCallExpression call,
            @NonNull UFunction method,
            @NonNull List<UAnnotation> annotations) {
        boolean handledResourceTypes = false;
        for (UAnnotation annotation : annotations) {
            String signature = annotation.getFqName();
            if (signature == null) {
                continue;
            }

            if (COLOR_INT_ANNOTATION.equals(signature)) {
                checkColor(context, argument);
            } else if (signature.equals(INT_RANGE_ANNOTATION)) {
                checkIntRange(context, annotation, argument, annotations);
            } else if (signature.equals(FLOAT_RANGE_ANNOTATION)) {
                checkFloatRange(context, annotation, argument);
            } else if (signature.equals(SIZE_ANNOTATION)) {
                checkSize(context, annotation, argument);
            } else if (signature.startsWith(PERMISSION_ANNOTATION)) {
                // PERMISSION_ANNOTATION, PERMISSION_ANNOTATION_READ, PERMISSION_ANNOTATION_WRITE
                // When specified on a parameter, that indicates that we're dealing with
                // a permission requirement on this *method* which depends on the value
                // supplied by this parameter
                checkParameterPermission(context, signature, call, method, argument);
            } else {
                // We only run @IntDef, @StringDef and @<Type>Res checks if we're not
                // running inside Android Studio / IntelliJ where there are already inspections
                // covering the same warnings (using IntelliJ's own data flow analysis); we
                // don't want to (a) create redundant warnings or (b) work harder than we
                // have to
                if (signature.equals(INT_DEF_ANNOTATION)) {
                    boolean flag = getAnnotationBooleanValue(annotation, TYPE_DEF_FLAG_ATTRIBUTE) == Boolean.TRUE;
                    checkTypeDefConstant(context, annotation, argument, null, flag,
                            annotations);
                } else if (signature.equals(STRING_DEF_ANNOTATION)) {
                    checkTypeDefConstant(context, annotation, argument, null, false,
                            annotations);
                } else if (signature.endsWith(RES_SUFFIX)) {
                    if (handledResourceTypes) {
                        continue;
                    }
                    handledResourceTypes = true;
                    EnumSet<ResourceType> types = null;
                    // Handle all resource type annotations in one go: there could be multiple
                    // resource type annotations specified on the same element; we need to
                    // know about them all up front.
                    for (UAnnotation a : annotations) {
                        String s = a.getFqName();
                        if (s != null && s.endsWith(RES_SUFFIX)) {
                            String typeString = s.substring(SUPPORT_ANNOTATIONS_PREFIX.length(),
                                    s.length() - RES_SUFFIX.length()).toLowerCase(Locale.US);
                            ResourceType type = ResourceType.getEnum(typeString);
                            if (type != null) {
                                if (types == null) {
                                    types = EnumSet.of(type);
                                } else {
                                    types.add(type);
                                }
                            } else if (typeString.equals("any")) { // @AnyRes
                                types = getAnyRes();
                                break;
                            }
                        }
                    }

                    if (types != null) {
                        checkResourceType(context, argument, types, call, method);
                    }
                }
            }
        }
    }

    private static EnumSet<ResourceType> getAnyRes() {
        EnumSet<ResourceType> types = EnumSet.allOf(ResourceType.class);
        types.remove(ResourceEvaluator.COLOR_INT_MARKER_TYPE);
        return types;
    }

    private void checkParameterPermission(
            @NonNull JavaContext context,
            @NonNull String signature,
            @NonNull UElement call,
            @NonNull UFunction method,
            @NonNull UExpression argument) {
        Operation operation = null;
        if (signature.equals(PERMISSION_ANNOTATION_READ)) {
            operation = READ;
        } else if (signature.equals(PERMISSION_ANNOTATION_WRITE)) {
            operation = WRITE;
        } else {
            UType type = argument.getExpressionType();
            if (type != null && type.matchesFqName(CLASS_INTENT)) {
                operation = ACTION;
            }
        }
        if (operation == null) {
            return;
        }
        Result result = PermissionFinder.findRequiredPermissions(operation, context, argument);
        if (result != null) {
            checkPermission(context, call, method, result, result.requirement);
        }
    }

    private static void checkColor(@NonNull JavaContext context, @NonNull UElement argument) {
        if (argument instanceof UIfExpression) {
            UIfExpression expression = (UIfExpression) argument;
            if (!expression.isStatement()) {
                if (expression.getThenBranch() != null) {
                    checkColor(context, expression.getThenBranch());
                }
                if (expression.getElseBranch() != null) {
                    checkColor(context, expression.getElseBranch());
                }
            }
            return;
        }

        EnumSet<ResourceType> types = ResourceEvaluator.getResourceTypes(context, argument);

        if (types != null && types.contains(COLOR)
                && !isIgnoredInIde(COLOR_USAGE, context, argument)) {
            String message = String.format(
                    "Should pass resolved color instead of resource id here: " +
                            "`getResources().getColor(%1$s)`", argument.originalString());
            context.report(COLOR_USAGE, argument, context.getLocation(argument), message);
        }
    }

    private static boolean isIgnoredInIde(@NonNull Issue issue, @NonNull JavaContext context,
            @NonNull UElement node) {
        // Historically, the IDE would treat *all* support annotation warnings as
        // handled by the id "ResourceType", so look for that id too for issues
        // deliberately suppressed prior to Android Studio 2.0.
        Issue synonym = Issue.create("ResourceType", issue.getBriefDescription(TextFormat.RAW),
                issue.getExplanation(TextFormat.RAW), issue.getCategory(), issue.getPriority(),
                issue.getDefaultSeverity(), issue.getImplementation());
        return context.getDriver().isSuppressed(context, synonym, node);
    }

    private void checkPermission(
            @NonNull JavaContext context,
            @NonNull UElement node,
            @Nullable UFunction method,
            @Nullable Result result,
            @NonNull PermissionRequirement requirement) {
        if (requirement.isConditional()) {
            return;
        }
        PermissionHolder permissions = getPermissions(context);
        if (!requirement.isSatisfied(permissions)) {
            // See if it looks like we're holding the permission implicitly by @RequirePermission
            // annotations in the surrounding context
            permissions  = addLocalPermissions(context, permissions, node);
            if (!requirement.isSatisfied(permissions)) {
                if (isIgnoredInIde(MISSING_PERMISSION, context, node)) {
                    return;
                }
                Operation operation;
                String name;
                if (result != null) {
                    name = result.name;
                    operation = result.operation;
                } else {
                    assert method != null;
                    UClass containingClass = UastUtils.getContainingClass(method);
                    if (containingClass != null) {
                        name = containingClass.getName() + "." + method.getName();
                    } else {
                        name = method.getName();
                    }
                    operation = Operation.CALL;
                }
                String message = getMissingPermissionMessage(requirement, name, permissions,
                        operation);
                context.report(MISSING_PERMISSION, node, context.getLocation(node), message);
            }
        } else if (requirement.isRevocable(permissions) &&
                context.getMainProject().getTargetSdkVersion().getFeatureLevel() >= 23) {

            boolean handlesMissingPermission = handlesSecurityException(node);

            // If not, check to see if the code is deliberately checking to see if the
            // given permission is available.
            if (!handlesMissingPermission) {
                UFunction methodNode = UastUtils.getContainingFunction(node);
                if (methodNode != null) {
                    CheckPermissionVisitor visitor = new CheckPermissionVisitor(node);
                    methodNode.accept(visitor);
                    handlesMissingPermission = visitor.checksPermission();
                }
            }

            if (!handlesMissingPermission && !isIgnoredInIde(MISSING_PERMISSION, context, node)) {
                String message = getUnhandledPermissionMessage();
                context.report(MISSING_PERMISSION, node, context.getLocation(node), message);
            }
        }
    }

    private static boolean handlesSecurityException(@NonNull UElement node) {
        // Ensure that the caller is handling a security exception
        // First check to see if we're inside a try/catch which catches a SecurityException
        // (or some wider exception than that). Check for nested try/catches too.
        UElement parent = node;
        while (true) {
            UTryExpression tryCatch = UastUtils
                    .getParentOfType(parent, UTryExpression.class, true);
            if (tryCatch == null) {
                break;
            } else {
                for (UCatchClause psiCatchSection : tryCatch.getCatchClauses()) {
                    for (UType type : psiCatchSection.getTypes()) {
                        if (isSecurityException(type)) {
                            return true;
                        }
                    }
                }

                parent = tryCatch;
            }
        }

        // If not, check to see if the method itself declares that it throws a
        // SecurityException or something wider.
        UFunction declaration = UastUtils.getParentOfType(parent, UFunction.class, false);
        if (declaration != null) {
            for (UType type : declaration.getThrows()) {
                if (isSecurityException(type)) {
                    return true;
                }
            }
        }

        return false;
    }

    @NonNull
    private static PermissionHolder addLocalPermissions(
            @NonNull JavaContext context,
            @NonNull PermissionHolder permissions,
            @NonNull UElement node) {
        // Accumulate @RequirePermissions available in the local context
        UFunction method = UastUtils.getContainingFunction(node);
        if (method == null) {
            return permissions;
        }
        UAnnotation annotation = UastUtils.findAnnotation(method, PERMISSION_ANNOTATION);
        permissions = mergeAnnotationPermissions(context, permissions, annotation);

        UClass containingClass = UastUtils.getContainingClass(method);
        if (containingClass != null) {
            annotation = UastUtils.findAnnotation(containingClass, PERMISSION_ANNOTATION);
            permissions = mergeAnnotationPermissions(context, permissions, annotation);
        }
        return permissions;
    }

    @NonNull
    private static PermissionHolder mergeAnnotationPermissions(
            @NonNull JavaContext context,
            @NonNull PermissionHolder permissions,
            @Nullable UAnnotation annotation) {
        if (annotation != null) {
            PermissionRequirement requirement = PermissionRequirement.create(context, annotation);
            permissions = SetPermissionLookup.join(permissions, requirement);
        }

        return permissions;
    }

    /** Returns the error message shown when a given call is missing one or more permissions */
    public static String getMissingPermissionMessage(@NonNull PermissionRequirement requirement,
            @NonNull String callName, @NonNull PermissionHolder permissions,
            @NonNull Operation operation) {
        return String.format("Missing permissions required %1$s %2$s: %3$s", operation.prefix(),
                callName, requirement.describeMissingPermissions(permissions));
    }

    /** Returns the error message shown when a revocable permission call is not properly handled */
    public static String getUnhandledPermissionMessage() {
        return "Call requires permission which may be rejected by user: code should explicitly "
                + "check to see if permission is available (with `checkPermission`) or explicitly "
                + "handle a potential `SecurityException`";
    }

    /**
     * Visitor which looks through a method, up to a given call (the one requiring a
     * permission) and checks whether it's preceeded by a call to checkPermission or
     * checkCallingPermission or enforcePermission etc.
     * <p>
     * Currently it only looks for the presence of this check; it does not perform
     * flow analysis to determine whether the check actually affects program flow
     * up to the permission call, or whether the check permission is checking for
     * permissions sufficient to satisfy the permission requirement of the target call,
     * or whether the check return value (== PERMISSION_GRANTED vs != PERMISSION_GRANTED)
     * is handled correctly, etc.
     */
    private static class CheckPermissionVisitor extends AbstractUastVisitor {
        private boolean mChecksPermission;
        private boolean mDone;
        private final UElement mTarget;

        public CheckPermissionVisitor(@NonNull UElement target) {
            mTarget = target;
        }

        @Override
        public boolean visitElement(UElement node) {
            return !mDone || super.visitElement(node);
        }

        @Override
        public boolean visitCallExpression(UCallExpression node) {
            if (node == mTarget) {
                mDone = true;
            }

            String name = node.getFunctionName();
            if (name != null
                    && (name.startsWith("check") || name.startsWith("enforce"))
                    && name.endsWith("Permission")) {
                mChecksPermission = true;
                mDone = true;
            }

            return !mDone || super.visitCallExpression(node);
        }

        public boolean checksPermission() {
            return mChecksPermission;
        }
    }

    private static boolean isSecurityException(@Nullable UType type) {
        // In earlier versions we checked not just for java.lang.SecurityException but
        // any super type as well, however that probably hides warnings in cases where
        // users don't want that; see http://b.android.com/182165
        //return context.getEvaluator().extendsClass(cls, "java.lang.SecurityException", false);
        return type != null && type.matchesFqName("java.lang.SecurityException");
    }

    private PermissionHolder mPermissions;

    private PermissionHolder getPermissions(
            @NonNull JavaContext context) {
        if (mPermissions == null) {
            Set<String> permissions = Sets.newHashSetWithExpectedSize(30);
            Set<String> revocable = Sets.newHashSetWithExpectedSize(4);
            LintClient client = context.getClient();
            // Gather permissions from all projects that contribute to the
            // main project.
            Project mainProject = context.getMainProject();
            for (File manifest : mainProject.getManifestFiles()) {
                addPermissions(client, permissions, revocable, manifest);
            }
            for (Project library : mainProject.getAllLibraries()) {
                for (File manifest : library.getManifestFiles()) {
                    addPermissions(client, permissions, revocable, manifest);
                }
            }

            AndroidVersion minSdkVersion = mainProject.getMinSdkVersion();
            AndroidVersion targetSdkVersion = mainProject.getTargetSdkVersion();
            mPermissions = new SetPermissionLookup(permissions, revocable, minSdkVersion,
                targetSdkVersion);
        }

        return mPermissions;
    }

    private static void addPermissions(@NonNull LintClient client,
            @NonNull Set<String> permissions,
            @NonNull Set<String> revocable,
            @NonNull File manifest) {
        Document document = XmlUtils.parseDocumentSilently(client.readFile(manifest), true);
        if (document == null) {
            return;
        }
        Element root = document.getDocumentElement();
        if (root == null) {
            return;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0, n = children.getLength(); i < n; i++) {
            Node item = children.item(i);
            if (item.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String nodeName = item.getNodeName();
            if (nodeName.equals(TAG_USES_PERMISSION)
                || nodeName.equals(TAG_USES_PERMISSION_SDK_23)
                || nodeName.equals(TAG_USES_PERMISSION_SDK_M)) {
                Element element = (Element)item;
                String name = element.getAttributeNS(ANDROID_URI, ATTR_NAME);
                if (!name.isEmpty()) {
                    permissions.add(name);
                }
            } else if (nodeName.equals(TAG_PERMISSION)) {
                Element element = (Element)item;
                String protectionLevel = element.getAttributeNS(ANDROID_URI,
                        ATTR_PROTECTION_LEVEL);
                if (VALUE_DANGEROUS.equals(protectionLevel)) {
                    String name = element.getAttributeNS(ANDROID_URI, ATTR_NAME);
                    if (!name.isEmpty()) {
                        revocable.add(name);
                    }
                }
            }
        }
    }

    private static void checkResult(
            @NonNull JavaContext context,
            @NonNull UElement node,
            @NonNull UAnnotation annotation) {
        if (skipParentheses(node.getParent()) instanceof UExpression) {
            String methodName = JavaContext.getMethodName(node);
            String suggested = getAnnotationStringValue(annotation, ATTR_SUGGEST);

            // Failing to check permissions is a potential security issue (and had an existing
            // dedicated issue id before which people may already have configured with a
            // custom severity in their LintOptions etc) so continue to use that issue
            // (which also has category Security rather than Correctness) for these:
            Issue issue = CHECK_RESULT;
            if (methodName != null && methodName.startsWith("check")
                    && methodName.contains("Permission")) {
                issue = CHECK_PERMISSION;
            }

            if (isIgnoredInIde(issue, context, node)) {
                return;
            }

            String message = String.format("The result of `%1$s` is not used",
                    methodName);
            if (suggested != null) {
                // TODO: Resolve suggest attribute (e.g. prefix annotation class if it starts
                // with "#" etc?
                message = String.format(
                        "The result of `%1$s` is not used; did you mean to call `%2$s`?",
                        methodName, suggested);
            }
            context.report(issue, node, context.getLocation(node), message);
        }
    }

    private static void checkThreading(
            @NonNull JavaContext context,
            @NonNull UElement node,
            @NonNull UFunction method,
            @NonNull String annotation) {
        String threadContext = getThreadContext(context, node);
        if (threadContext != null && !isCompatibleThread(threadContext, annotation)
                && !isIgnoredInIde(THREAD, context, node)) {
            String message = String.format("%1$s %2$s must be called from the `%3$s` thread, currently inferred thread is `%4$s` thread",
                    method.getKind() == UastFunctionKind.CONSTRUCTOR ? "Constructor" : "Method",
                    method.getName(), describeThread(annotation), describeThread(threadContext));
            context.report(THREAD, node, context.getLocation(node), message);
        }
    }

    @NonNull
    public static String describeThread(@NonNull String annotation) {
        if (UI_THREAD_ANNOTATION.equals(annotation)) {
            return "UI";
        }
        else if (MAIN_THREAD_ANNOTATION.equals(annotation)) {
            return "main";
        }
        else if (BINDER_THREAD_ANNOTATION.equals(annotation)) {
            return "binder";
        }
        else if (WORKER_THREAD_ANNOTATION.equals(annotation)) {
            return "worker";
        } else {
            return "other";
        }
    }

    /** returns true if the two threads are compatible */
    public static boolean isCompatibleThread(@NonNull String thread1, @NonNull String thread2) {
        if (thread1.equals(thread2)) {
            return true;
        }

        // Allow @UiThread and @MainThread to be combined
        if (thread1.equals(UI_THREAD_ANNOTATION)) {
            if (thread2.equals(MAIN_THREAD_ANNOTATION)) {
                return true;
            }
        } else if (thread1.equals(MAIN_THREAD_ANNOTATION)) {
            if (thread2.equals(UI_THREAD_ANNOTATION)) {
                return true;
            }
        }

        return false;
    }

    /** Attempts to infer the current thread context at the site of the given method call */
    @Nullable
    private static String getThreadContext(
            @NonNull JavaContext context,
            @NonNull UElement methodCall) {
        UFunction parentFunction = UastUtils.getParentOfType(methodCall, UFunction.class, true);
        if (parentFunction != null) {
            UClass cls = UastUtils.getContainingClass(parentFunction);

            List<UFunction> functions = new ArrayList<UFunction>(1);
            functions.add(parentFunction);
            functions.addAll(parentFunction.getOverriddenDeclarations(context));

            for (UFunction function : functions) {
                for (UAnnotation annotation : context.getAnnotationsWithExternal(function)) {
                    String name = annotation.getFqName();
                    if (name != null && name.startsWith(SUPPORT_ANNOTATIONS_PREFIX)
                            && name.endsWith(THREAD_SUFFIX)) {
                        return name;
                    }
                }
            }

            // See if we're extending a class with a known threading context

            List<UClass> classes = new ArrayList<UClass>(1);
            if (cls != null) {
                classes.add(cls);
                classes.addAll(cls.getOverriddenDeclarations(context));
            }

            for (UClass clazz : classes) {
                for (UAnnotation annotation : context.getAnnotationsWithExternal(clazz)) {
                    String name = annotation.getFqName();
                    if (name != null && name.startsWith(SUPPORT_ANNOTATIONS_PREFIX)
                            && name.endsWith(THREAD_SUFFIX)) {
                        return name;
                    }
                }
            }
        }

        // In the future, we could also try to infer the threading context using
        // other heuristics. For example, if we're in a method with unknown threading
        // context, but we see that the method is called by another method with a known
        // threading context, we can infer that that threading context is the context for
        // this thread too (assuming the call is direct).

        return null;
    }

    private static boolean isNumber(@NonNull UElement argument) {
        if (argument instanceof ULiteralExpression) {
            Object value = ((ULiteralExpression) argument).getValue();
            return value instanceof Number;
        } else if (argument instanceof UPrefixExpression) {
            UPrefixExpression expression = (UPrefixExpression) argument;
            UExpression operand = expression.getOperand();
            return isNumber(operand);
        } else {
            return false;
        }
    }

    private static boolean isZero(@NonNull UElement argument) {
        if (argument instanceof ULiteralExpression) {
            Object value = ((ULiteralExpression) argument).getValue();
            return value instanceof Number && ((Number)value).intValue() == 0;
        }
        return false;
    }

    private static boolean isMinusOne(@NonNull UElement argument) {
        if (argument instanceof UPrefixExpression) {
            UPrefixExpression expression = (UPrefixExpression) argument;
            UExpression operand = expression.getOperand();
            if (operand instanceof ULiteralExpression &&
                    expression.getOperator() == UastPrefixOperator.UNARY_MINUS) {
                Object value = ((ULiteralExpression) operand).getValue();
                return value instanceof Number && ((Number) value).intValue() == 1;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private static void checkResourceType(
            @NonNull JavaContext context,
            @NonNull UElement argument,
            @NonNull EnumSet<ResourceType> expectedType,
            @NonNull UCallExpression call,
            @NonNull UFunction calledMethod) {
        EnumSet<ResourceType> actual = ResourceEvaluator.getResourceTypes(context, argument);

        if (actual == null && (!isNumber(argument) || isZero(argument) || isMinusOne(argument)) ) {
            return;
        } else if (actual != null && (!Sets.intersection(actual, expectedType).isEmpty()
                || expectedType.contains(DRAWABLE)
                && (actual.contains(COLOR) || actual.contains(MIPMAP)))) {
            return;
        }

        if (isIgnoredInIde(RESOURCE_TYPE, context, argument)) {
            return;
        }

        if (expectedType.contains(ResourceType.STYLEABLE) && (expectedType.size() == 1)
                && JavaEvaluator.isMemberInClass(calledMethod,
                        "android.content.res.TypedArray")
                && (UastExpressionUtils.isFunctionCall(call))
                && typeArrayFromArrayLiteral(UastUtils.getQualifiedParentOrThis(call), context)) {
            // You're generally supposed to provide a styleable to the TypedArray methods,
            // but you're also allowed to supply an integer array
            return;
        }

        String message;
        if (actual != null && actual.size() == 1 && actual.contains(
                ResourceEvaluator.COLOR_INT_MARKER_TYPE)) {
            message = "Expected a color resource id (`R.color.`) but received an RGB integer";
        } else if (expectedType.contains(ResourceEvaluator.COLOR_INT_MARKER_TYPE)) {
            message = String.format("Should pass resolved color instead of resource id here: " +
                    "`getResources().getColor(%1$s)`", argument.originalString());
        } else if (expectedType.size() < ResourceType.getNames().length - 1) {
            message = String.format("Expected resource of type %1$s",
                    Joiner.on(" or ").join(expectedType));
        } else {
            message = "Expected resource identifier (`R`.type.`name`)";
        }
        context.report(RESOURCE_TYPE, argument, context.getLocation(argument), message);
    }

    /**
     * Returns true if the node is pointing to a TypedArray whose value was obtained
     * from an array literal
     */
    public static boolean typeArrayFromArrayLiteral(@Nullable UElement node, UastContext context) {
        if (node != null && UastExpressionUtils.isFunctionCall(node)) {
            UCallExpression call = (UCallExpression) node;
            if (call.matchesFunctionName("obtainStyledAttributes")
                    && call.getValueArgumentCount() > 0) {
                List<UExpression> args = call.getValueArguments();

                int argIndex;
                if (args.size() == 1) {
                    // obtainStyledAttributes(int[] attrs)
                    argIndex = 0;
                } else if (args.size() == 2) {
                    UFunction function = call.resolve(context);
                    if (function == null) {
                        return false;
                    }

                    List<UVariable> params = function.getValueParameters();
                    if (params.size() < 2) {
                        return false;
                    }

                    // obtainStyledAttributes(AttributeSet set, int[] attrs)
                    // obtainStyledAttributes(int resid, int[] attrs)
                    for (argIndex = 0; argIndex < args.size(); argIndex++) {
                        UResolvedType type = params.get(argIndex).getType().resolve();
                        if (type instanceof UResolvedArrayType) {
                            break;
                        }
                    }
                    if (argIndex == args.size()) {
                        return false;
                    }
                } else if (args.size() == 4) {
                    // obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes)
                    argIndex = 1;
                } else {
                    return false;
                }

                return ConstantEvaluator.isArrayLiteral(args.get(argIndex), context);
            }
            return false;
        } else if (node instanceof UReferenceExpression) {
            UDeclaration resolved = ((UReferenceExpression) node).resolve(context);
            if (resolved instanceof UVariable) {
                UVariable variable = (UVariable) resolved;
                UExpression lastAssignment =
                        UastLintUtils.findLastAssignment(variable, node, context);

                if (lastAssignment != null) {
                    return typeArrayFromArrayLiteral(lastAssignment, context);
                }
            }
        } else if (UastExpressionUtils.isNewArrayWithInitializer(node)) {
            return true;
        } else if (UastExpressionUtils.isNewArrayWithDimensions(node)) {
            return true;
        } else if (node instanceof UParenthesizedExpression) {
            UParenthesizedExpression parenthesizedExpression = (UParenthesizedExpression) node;
            UExpression expression = parenthesizedExpression.getExpression();
            return typeArrayFromArrayLiteral(expression, context);
        } else if (UastExpressionUtils.isTypeCast(node)) {
            UBinaryExpressionWithType castExpression = (UBinaryExpressionWithType) node;
            UExpression operand = castExpression.getOperand();
            if (operand != null) {
                return typeArrayFromArrayLiteral(operand, context);
            }
        }

        return false;
    }

    private static void checkIntRange(
            @NonNull JavaContext context,
            @NonNull UAnnotation annotation,
            @NonNull UElement argument,
            @NonNull List<UAnnotation> allAnnotations) {
        String message = getIntRangeError(context, annotation, argument);
        if (message != null) {
            if (findIntDef(allAnnotations) != null) {
                // Don't flag int range errors if there is an int def annotation there too;
                // there could be a valid @IntDef constant. (The @IntDef check will
                // perform range validation by calling getIntRange.)
                return;
            }

            if (isIgnoredInIde(RANGE, context, argument)) {
                return;
            }

            context.report(RANGE, argument, context.getLocation(argument), message);
        }
    }

    @Nullable
    private static String getIntRangeError(
            @NonNull JavaContext context,
            @NonNull UAnnotation annotation,
            @NonNull UElement argument) {
        if (UastExpressionUtils.isNewArrayWithInitializer(argument)) {
            UCallExpression initializer = (UCallExpression) argument;
            for (UExpression expression : initializer.getValueArguments()) {
                String error = getIntRangeError(context, annotation, expression);
                if (error != null) {
                    return error;
                }
            }
        }

        Object object = ConstantEvaluator.evaluate(context, argument);
        if (!(object instanceof Number)) {
            return null;
        }
        long value = ((Number)object).longValue();
        long from = getLongAttribute(annotation, ATTR_FROM, Long.MIN_VALUE);
        long to = getLongAttribute(annotation, ATTR_TO, Long.MAX_VALUE);

        return getIntRangeError(value, from, to);
    }

    /**
     * Checks whether a given integer value is in the allowed range, and if so returns
     * null; otherwise returns a suitable error message.
     */
    private static String getIntRangeError(long value, long from, long to) {
        String message = null;
        if (value < from || value > to) {
            StringBuilder sb = new StringBuilder(20);
            if (value < from) {
                sb.append("Value must be \u2265 ");
                sb.append(Long.toString(from));
            } else {
                assert value > to;
                sb.append("Value must be \u2264 ");
                sb.append(Long.toString(to));
            }
            sb.append(" (was ").append(value).append(')');
            message = sb.toString();
        }
        return message;
    }

    private static void checkFloatRange(
            @NonNull JavaContext context,
            @NonNull UAnnotation annotation,
            @NonNull UElement argument) {
        Object object = ConstantEvaluator.evaluate(context, argument);
        if (!(object instanceof Number)) {
            return;
        }
        double value = ((Number)object).doubleValue();
        double from = getDoubleAttribute(annotation, ATTR_FROM, Double.NEGATIVE_INFINITY);
        double to = getDoubleAttribute(annotation, ATTR_TO, Double.POSITIVE_INFINITY);
        boolean fromInclusive = getBoolean(annotation, ATTR_FROM_INCLUSIVE, true);
        boolean toInclusive = getBoolean(annotation, ATTR_TO_INCLUSIVE, true);

        String message = getFloatRangeError(value, from, to, fromInclusive, toInclusive, argument);
        if (message != null && !isIgnoredInIde(RANGE, context, argument)) {
            context.report(RANGE, argument, context.getLocation(argument), message);
        }
    }

    /**
     * Checks whether a given floating point value is in the allowed range, and if so returns
     * null; otherwise returns a suitable error message.
     */
    @Nullable
    private static String getFloatRangeError(double value, double from, double to,
            boolean fromInclusive, boolean toInclusive, @NonNull UElement node) {
        if (!((fromInclusive && value >= from || !fromInclusive && value > from) &&
                (toInclusive && value <= to || !toInclusive && value < to))) {
            StringBuilder sb = new StringBuilder(20);
            if (from != Double.NEGATIVE_INFINITY) {
                if (to != Double.POSITIVE_INFINITY) {
                    if (fromInclusive && value < from || !fromInclusive && value <= from) {
                        sb.append("Value must be ");
                        if (fromInclusive) {
                            sb.append('\u2265'); // >= sign
                        } else {
                            sb.append('>');
                        }
                        sb.append(' ');
                        sb.append(Double.toString(from));
                    } else {
                        assert toInclusive && value > to || !toInclusive && value >= to;
                        sb.append("Value must be ");
                        if (toInclusive) {
                            sb.append('\u2264'); // <= sign
                        } else {
                            sb.append('<');
                        }
                        sb.append(' ');
                        sb.append(Double.toString(to));
                    }
                } else {
                    sb.append("Value must be ");
                    if (fromInclusive) {
                        sb.append('\u2265'); // >= sign
                    } else {
                        sb.append('>');
                    }
                    sb.append(' ');
                    sb.append(Double.toString(from));
                }
            } else if (to != Double.POSITIVE_INFINITY) {
                sb.append("Value must be ");
                if (toInclusive) {
                    sb.append('\u2264'); // <= sign
                } else {
                    sb.append('<');
                }
                sb.append(' ');
                sb.append(Double.toString(to));
            }
            sb.append(" (was ");
            if (node instanceof ULiteralExpression) {
                // Use source text instead to avoid rounding errors involved in conversion, e.g
                //    Error: Value must be > 2.5 (was 2.490000009536743) [Range]
                //    printAtLeastExclusive(2.49f); // ERROR
                //                          ~~~~~
                String str = node.originalString();
                sb.append(str);
            } else {
                sb.append(value);
            }
            sb.append(')');
            return sb.toString();
        }
        return null;
    }

    private static void checkSize(
            @NonNull JavaContext context,
            @NonNull UAnnotation annotation,
            @NonNull UElement argument) {
        int actual;
        boolean isString = false;

        // TODO: Collections syntax, e.g. Arrays.asList => param count, emptyList=0, singleton=1, etc
        // TODO: Flow analysis
        // No flow analysis for this check yet, only checking literals passed in as parameters

        if (UastExpressionUtils.isNewArrayWithInitializer(argument)) {
            actual = ((UCallExpression) argument).getValueArgumentCount();
        } else {
            Object object = ConstantEvaluator.evaluate(context, argument);
            // Check string length
            if (object instanceof String) {
                actual = ((String)object).length();
                isString = true;
            } else {
                return;
            }
        }
        long exact = getLongAttribute(annotation, ATTR_VALUE, -1);
        long min = getLongAttribute(annotation, ATTR_MIN, Long.MIN_VALUE);
        long max = getLongAttribute(annotation, ATTR_MAX, Long.MAX_VALUE);
        long multiple = getLongAttribute(annotation, ATTR_MULTIPLE, 1);

        String unit;
        if (isString) {
            unit = "length";
        } else {
            unit = "size";
        }
        String message = getSizeError(actual, exact, min, max, multiple, unit);
        if (message != null && !isIgnoredInIde(RANGE, context, argument)) {
            context.report(RANGE, argument, context.getLocation(argument), message);
        }
    }

    /**
     * Checks whether a given size follows the given constraints, and if so returns
     * null; otherwise returns a suitable error message.
     */
    private static String getSizeError(long actual, long exact, long min, long max, long multiple,
            @NonNull String unit) {
        String message = null;
        if (exact != -1) {
            if (exact != actual) {
                message = String.format("Expected %1$s %2$d (was %3$d)",
                        unit, exact, actual);
            }
        } else if (actual < min || actual > max) {
            StringBuilder sb = new StringBuilder(20);
            if (actual < min) {
                sb.append("Expected ").append(unit).append(" \u2265 ");
                sb.append(Long.toString(min));
            } else {
                assert actual > max;
                sb.append("Expected ").append(unit).append(" \u2264 ");
                sb.append(Long.toString(max));
            }
            sb.append(" (was ").append(actual).append(')');
            message = sb.toString();
        } else if (actual % multiple != 0) {
            message = String.format("Expected %1$s to be a multiple of %2$d (was %3$d "
                            + "and should be either %4$d or %5$d)",
                    unit, multiple, actual, (actual / multiple) * multiple,
                    (actual / multiple + 1) * multiple);
        }
        return message;
    }

    @Nullable
    private static UAnnotation findIntRange(
            @NonNull List<UAnnotation> annotations) {
        for (UAnnotation annotation : annotations) {
            if (INT_RANGE_ANNOTATION.equals(annotation.getFqName())) {
                return annotation;
            }
        }

        return null;
    }

    @Nullable
    static UAnnotation findIntDef(@NonNull List<UAnnotation> annotations) {
        for (UAnnotation annotation : annotations) {
            if (INT_DEF_ANNOTATION.equals(annotation.getFqName())) {
                return annotation;
            }
        }

        return null;
    }

    private static void checkTypeDefConstant(
            @NonNull JavaContext context,
            @NonNull UAnnotation annotation,
            @Nullable UElement argument,
            @Nullable UElement errorNode,
            boolean flag,
            @NonNull List<UAnnotation> allAnnotations) {
        if (argument == null) {
            return;
        }
        if (argument instanceof ULiteralExpression) {
            Object value = ((ULiteralExpression) argument).getValue();
            if (value == null) {
                // Accepted for @StringDef
                //noinspection UnnecessaryReturnStatement
                return;
            } else if (value instanceof String) {
                String string = (String) value;
                checkTypeDefConstant(context, annotation, argument, errorNode, false, string,
                        allAnnotations);
            } else if (value instanceof Integer || value instanceof Long) {
                long v = value instanceof Long ? ((Long) value) : ((Integer) value).longValue();
                if (flag && v == 0) {
                    // Accepted for a flag @IntDef
                    return;
                }

                checkTypeDefConstant(context, annotation, argument, errorNode, flag, value,
                        allAnnotations);
            }
        } else if (isMinusOne(argument)) {
            // -1 is accepted unconditionally for flags
            if (!flag) {
                reportTypeDef(context, annotation, argument, errorNode, allAnnotations);
            }
        } else if (argument instanceof UPrefixExpression) {
            UPrefixExpression expression = (UPrefixExpression) argument;
            if (flag) {
                checkTypeDefConstant(context, annotation, expression.getOperand(),
                        errorNode, true, allAnnotations);
            } else {
                UastPrefixOperator operator = expression.getOperator();
                if (operator == UastPrefixOperator.BITWISE_NOT) {
                    if (isIgnoredInIde(TYPE_DEF, context, expression)) {
                        return;
                    }
                    context.report(TYPE_DEF, expression, context.getLocation(expression),
                            "Flag not allowed here");
                } else if (operator == UastPrefixOperator.UNARY_MINUS) {
                    reportTypeDef(context, annotation, argument, errorNode, allAnnotations);
                }
            }
        } else if (argument instanceof UParenthesizedExpression) {
            UExpression expression = ((UParenthesizedExpression) argument).getExpression();
            checkTypeDefConstant(context, annotation, expression, errorNode, flag, allAnnotations);
        } else if (argument instanceof UIfExpression) {
            UIfExpression expression = (UIfExpression) argument;
            UExpression thenBranch = expression.getThenBranch();
            if (thenBranch != null) {
                checkTypeDefConstant(context, annotation, thenBranch, errorNode, flag,
                        allAnnotations);
            }
            UExpression elseBranch = expression.getElseBranch();
            if (elseBranch != null) {
                checkTypeDefConstant(context, annotation, elseBranch, errorNode, flag,
                        allAnnotations);
            }
        } else if (argument instanceof UBinaryExpression) {
            // If it's ?: then check both the if and else clauses
            UBinaryExpression expression = (UBinaryExpression) argument;
            if (flag) {
                checkTypeDefConstant(context, annotation, expression.getLeftOperand(),
                        errorNode, true, allAnnotations);
                checkTypeDefConstant(context, annotation, expression.getRightOperand(),
                        errorNode, true, allAnnotations);
            } else {
                UastBinaryOperator operator = expression.getOperator();
                if (operator == UastBinaryOperator.BITWISE_AND
                        || operator == UastBinaryOperator.BITWISE_OR
                        || operator == UastBinaryOperator.BITWISE_XOR) {
                    if (isIgnoredInIde(TYPE_DEF, context, expression)) {
                        return;
                    }
                    context.report(TYPE_DEF, expression, context.getLocation(expression),
                            "Flag not allowed here");
                }
            }
        } else if (argument instanceof UReferenceExpression) {
            UDeclaration resolved = ((UReferenceExpression) argument).resolve(context);
            if (resolved instanceof UVariable) {
                UVariable variable = (UVariable) resolved;

                if (variable.getType().resolve() instanceof UResolvedArrayType) {
                    // It's pointing to an array reference; we can't check these individual
                    // elements (because we can't jump from ResolvedNodes to AST elements; this
                    // is part of the motivation for the PSI change in lint 2.0), but we also
                    // don't want to flag it as invalid.
                    return;
                }

                // If it's a constant (static/final) check that it's one of the allowed ones
                if (variable.hasModifier(UastModifier.STATIC)
                        && variable.hasModifier(UastModifier.IMMUTABLE)) {
                    checkTypeDefConstant(context, annotation, argument,
                            errorNode != null ? errorNode : argument,
                            flag, resolved, allAnnotations);
                } else {
                    UExpression lastAssignment =
                            UastLintUtils.findLastAssignment(variable, argument, context);

                    if (lastAssignment != null) {
                        checkTypeDefConstant(context, annotation,
                                lastAssignment,
                                errorNode != null ? errorNode : argument, flag,
                                allAnnotations);
                    }
                }
            }
        } else if (UastExpressionUtils.isNewArrayWithInitializer(argument)) {
            UCallExpression call = (UCallExpression) argument;

            UResolvedType maybeArrayType = call.resolveTypeOrEmpty(context).resolve();
            if (maybeArrayType instanceof UResolvedArrayType) {
                UResolvedArrayType arrayType = (UResolvedArrayType) maybeArrayType;
                UType deepType = arrayType.findDeepElementType().getType();

                if (deepType.isInt() || deepType.isLong()) {
                    for (UExpression expression : call.getValueArguments()) {
                        checkTypeDefConstant(context, annotation, expression, errorNode, flag,
                                allAnnotations);
                    }
                }
            }
        }
    }

    private static void checkTypeDefConstant(@NonNull JavaContext context,
            @NonNull UAnnotation annotation, @NonNull UElement argument,
            @Nullable UElement errorNode, boolean flag, Object value,
            @NonNull List<UAnnotation> allAnnotations) {
        UAnnotation rangeAnnotation = findIntRange(allAnnotations);
        if (rangeAnnotation != null) {
            // Allow @IntRange on this number
            if (getIntRangeError(context, rangeAnnotation, argument) == null) {
                return;
            }
        }

        UConstantValue<?> allowed = getAnnotationValue(annotation);
        if (allowed == null) {
            return;
        }

        if (allowed instanceof UArrayValue) {
            UArrayValue initializerExpression = (UArrayValue) allowed;
            List<UConstantValue<?>> initializers = initializerExpression.getValue();
            for (UConstantValue<?> expression : initializers) {
                if (expression instanceof USimpleConstantValue<?>) {
                    if (value.equals(((USimpleConstantValue) expression).getValue())) {
                        return;
                    }
                } else if (expression instanceof UTypeValue) {
                    UDeclaration resolved = ((UTypeValue) expression)
                            .getValue().resolveToClass(context);
                    if (resolved != null && resolved.equals(value)) {
                        return;
                    }
                } else if (expression instanceof UExpressionValue) {
                    UDeclaration resolved = UastUtils.resolveIfCan(
                            ((UExpressionValue) expression).getValue(), context);
                    if (resolved != null && resolved.equals(value)) {
                        return;
                    }
                }
            }

            if (value instanceof UVariable) {
                UVariable variable = (UVariable) value;
                UExpression initializer = variable.getInitializer();
                if (initializer != null) {
                    // Special case for -1, -2, ...
                    if (initializer instanceof UPrefixExpression) {
                        UPrefixExpression prefixExpression = (UPrefixExpression) initializer;
                        if (prefixExpression.getOperator() == UastPrefixOperator.UNARY_MINUS) {
                            UExpression operand = prefixExpression.getOperand();
                            if (UastLiteralUtils.isIntegralLiteral(operand)) {
                                Object v = ((ULiteralExpression) operand).getValue();
                                assert v != null;
                                Number num;
                                if (v instanceof Long) {
                                    long l = (Long) v;
                                    num = -l;
                                } else {
                                    int i = ((Number) v).intValue();
                                    num = -i;
                                }
                                checkTypeDefConstant(context, annotation, initializer, errorNode,
                                        flag, num, allAnnotations);
                                return;
                            }
                        }
                    }
                    checkTypeDefConstant(context, annotation, initializer, errorNode,
                            flag, allAnnotations);
                    return;
                }
            }

            reportTypeDef(context, argument, errorNode, flag,
                    initializers, allAnnotations);
        }
    }

    private static void reportTypeDef(@NonNull JavaContext context,
            @NonNull UAnnotation annotation, @NonNull UElement argument,
            @Nullable UElement errorNode, @NonNull List<UAnnotation> allAnnotations) {
        //    reportTypeDef(context, argument, errorNode, false, allowedValues, allAnnotations);
        UConstantValue<?> allowed = getAnnotationValue(annotation);
        if (allowed instanceof UArrayValue) {
            UArrayValue initializerExpression = (UArrayValue) allowed;
            List<UConstantValue<?>> initializers = initializerExpression.getValue();
            reportTypeDef(context, argument, errorNode, false, initializers, allAnnotations);
        }
    }

    private static void reportTypeDef(@NonNull JavaContext context, @NonNull UElement node,
            @Nullable UElement errorNode, boolean flag,
            @NonNull List<UConstantValue<?>> allowedValues,
            @NonNull List<UAnnotation> allAnnotations) {
        if (errorNode == null) {
            errorNode = node;
        }
        if (isIgnoredInIde(TYPE_DEF, context, errorNode)) {
            return;
        }

        String values = listAllowedValues(allowedValues, context);
        String message;
        if (flag) {
            message = "Must be one or more of: " + values;
        } else {
            message = "Must be one of: " + values;
        }

        UAnnotation rangeAnnotation = findIntRange(allAnnotations);
        if (rangeAnnotation != null) {
            // Allow @IntRange on this number
            String rangeError = getIntRangeError(context, rangeAnnotation, node);
            if (rangeError != null && !rangeError.isEmpty()) {
                message += " or " + Character.toLowerCase(rangeError.charAt(0))
                        + rangeError.substring(1);
            }
        }

        context.report(TYPE_DEF, errorNode, context.getLocation(errorNode), message);
    }

    @Nullable
    private static UConstantValue<?> getAnnotationValue(@NonNull UAnnotation annotation) {
        UConstantValue<?> value = annotation.getValue(ATTR_VALUE);
        if (value == null) {
            value = annotation.getValue(null);
        }
        return value;
    }

    private static String listAllowedValues(
            @NonNull List<UConstantValue<?>> allowedValues,
            @NonNull  UastContext context) {
        StringBuilder sb = new StringBuilder();
        for (UConstantValue<?> allowedValue : allowedValues) {
            String s = null;
            UExpression original = allowedValue.getOriginal();
            if (original instanceof UReferenceExpression) {
                UDeclaration resolved = ((UReferenceExpression) original).resolve(context);
                if (resolved instanceof UVariable
                        && ((UVariable) resolved).getKind() == UastVariableKind.MEMBER) {
                    UVariable field = (UVariable) resolved;
                    UClass containingClass = UastUtils.getContainingClass(field);
                    String containingClassName = containingClass != null
                            ? containingClass.getName()
                            : null;

                    if (containingClassName == null) {
                        continue;
                    }

                    s = containingClassName + "." + field.getName();
                }
            }
            if (s == null) {
                Object value = allowedValue.getValue();
                if (value instanceof UElement) {
                    s = ((UElement) value).renderString();
                } else {
                    s = value.toString();
                }
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    static double getDoubleAttribute(@NonNull UAnnotation annotation,
            @NonNull String name, double defaultValue) {
        Double value = getAnnotationDoubleValue(annotation, name);
        if (value != null) {
            return value;
        }

        return defaultValue;
    }

    static long getLongAttribute(@NonNull UAnnotation annotation,
            @NonNull String name, long defaultValue) {
        Long value = getAnnotationLongValue(annotation, name);
        if (value != null) {
            return value;
        }

        return defaultValue;
    }

    static boolean getBoolean(@NonNull UAnnotation annotation,
            @NonNull String name, boolean defaultValue) {
        Boolean value = getAnnotationBooleanValue(annotation, name);
        if (value != null) {
            return value;
        }

        return defaultValue;
    }

    @NonNull
    static List<UAnnotation> filterRelevantAnnotations(@NonNull List<UAnnotation> annotations,
            JavaContext context) {
        if (annotations.isEmpty()) {
            return Collections.emptyList();
        }

        int length = annotations.size();
        List<UAnnotation> result = null;

        for (UAnnotation annotation : annotations) {
            String signature = annotation.getFqName();
            if (signature == null || signature.startsWith("java.")) {
                // @Override, @SuppressWarnings etc. Ignore
                continue;
            }

            if (signature.startsWith(SUPPORT_ANNOTATIONS_PREFIX)) {
                // Bail on the nullness annotations early since they're the most commonly
                // defined ones. They're not analyzed in lint yet.
                if (signature.endsWith(".Nullable") || signature.endsWith(".NonNull")) {
                    continue;
                }

                // Common case: there's just one annotation; no need to create a list copy
                if (length == 1) {
                    return annotations;
                }
                if (result == null) {
                    result = new ArrayList<UAnnotation>(2);
                }
                result.add(annotation);
            }

            // Special case @IntDef and @StringDef: These are used on annotations
            // themselves. For example, you create a new annotation named @foo.bar.Baz,
            // annotate it with @IntDef, and then use @foo.bar.Baz in your signatures.
            // Here we want to map from @foo.bar.Baz to the corresponding int def.
            // Don't need to compute this if performing @IntDef or @StringDef lookup
            UClass cls = annotation.resolve(context);
            if (cls == null) {
                continue;
            }

            List<UAnnotation> innerAnnotations = context.getAnnotationsWithExternal(cls);
            for (int j = 0; j < innerAnnotations.size(); j++) {
                UAnnotation inner = innerAnnotations.get(j);
                String a = inner.getFqName();
                if (a == null) {
                    continue;
                }
                if (a.equals(INT_DEF_ANNOTATION)
                        || a.equals(PERMISSION_ANNOTATION)
                        || a.equals(INT_RANGE_ANNOTATION)
                        || a.equals(STRING_DEF_ANNOTATION)) {
                    if (length == 1 && j == innerAnnotations.size() - 1) {
                        return innerAnnotations;
                    }
                    if (result == null) {
                        result = new ArrayList<UAnnotation>(2);
                    }
                    result.add(inner);
                }
            }
        }

        return result != null ? result : Collections.<UAnnotation>emptyList();
    }

    // ---- Implements UastScanner ----

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        List<Class<? extends UElement>> types = new ArrayList<Class<? extends UElement>>(3);
        types.add(UCallExpression.class);
        //types.add(PsiMethodCallExpression.class);
        //types.add(PsiNewExpression.class);
        types.add(UVariable.class);
        return types;
    }

    @Nullable
    @Override
    public UastVisitor createUastVisitor(@NonNull JavaContext context) {
        return new CallVisitor(context);
    }

    private class CallVisitor extends AbstractUastVisitor {
        private final JavaContext mContext;

        public CallVisitor(JavaContext context) {
            mContext = context;
        }

        @Override
        public boolean visitCallExpression(UCallExpression call) {
            UFunction method = call.resolve(mContext);
            if (method != null) {
                checkCall(method, call);
            }

            return super.visitCallExpression(call);
        }

        public void checkCall(UFunction function, UCallExpression call) {
            List<UAnnotation> annotations = filterRelevantAnnotations(
                    UastLintUtils.getAnnotationWithOverriddenWithExternal(function, mContext), mContext);
            for (UAnnotation annotation : annotations) {
                checkMethodAnnotation(mContext, function, call, annotation);
            }

            // Look for annotations on the class as well: these trickle
            // down to all the methods in the class
            UClass containingClass = UastUtils.getContainingClass(function);
            if (containingClass != null) {
                annotations = mContext.getAnnotationsWithExternal(containingClass);
                annotations = filterRelevantAnnotations(annotations, mContext);
                for (UAnnotation annotation : annotations) {
                    checkMethodAnnotation(mContext, function, call, annotation);
                }
            }

            List<UExpression> arguments = call.getValueArguments();
            List<UVariable> parameters = function.getValueParameters();
            for (int i = 0, n = Math.min(parameters.size(), arguments.size());
                    i < n;
                    i++) {
                UExpression argument = arguments.get(i);
                annotations = mContext.getParameterAnnotationsWithExternal(function, i);
                annotations = filterRelevantAnnotations(annotations, mContext);
                checkParameterAnnotations(mContext, argument, call, function, annotations);
            }
            // last parameter is varargs (same parameter annotations)
            for (int i = parameters.size(); i < arguments.size(); i++) {
                UExpression argument = arguments.get(i);
                checkParameterAnnotations(mContext, argument, call, function, annotations);
            }
        }
    }
}
