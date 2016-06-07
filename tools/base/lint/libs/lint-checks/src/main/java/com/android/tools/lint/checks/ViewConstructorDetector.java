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

import static com.android.SdkConstants.CLASS_ATTRIBUTE_SET;
import static com.android.SdkConstants.CLASS_CONTEXT;

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

import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastModifier;
import org.jetbrains.uast.UastUtils;

import java.util.Collections;
import java.util.List;

/**
 * Looks for custom views that do not define the view constructors needed by UI builders
 */
public class ViewConstructorDetector extends Detector implements Detector.UastScanner {
    /** The main issue discovered by this detector */
    public static final Issue ISSUE = Issue.create(
            "ViewConstructor", //$NON-NLS-1$
            "Missing View constructors for XML inflation",

            "Some layout tools (such as the Android layout editor) need to " +
            "find a constructor with one of the following signatures:\n" +
            "* `View(Context context)`\n" +
            "* `View(Context context, AttributeSet attrs)`\n" +
            "* `View(Context context, AttributeSet attrs, int defStyle)`\n" +
            "\n" +
            "If your custom view needs to perform initialization which does not apply when " +
            "used in a layout editor, you can surround the given code with a check to " +
            "see if `View#isInEditMode()` is false, since that method will return `false` " +
            "at runtime but true within a user interface editor.",

            Category.USABILITY,
            3,
            Severity.WARNING,
            new Implementation(
                    ViewConstructorDetector.class,
                    Scope.JAVA_FILE_SCOPE));

    /** Constructs a new {@link ViewConstructorDetector} check */
    public ViewConstructorDetector() {
    }

    // ---- Implements UastScanner ----

    private static boolean isXmlConstructor(@NonNull UFunction function) {
        // Accept
        //   android.content.Context
        //   android.content.Context,android.util.AttributeSet
        //   android.content.Context,android.util.AttributeSet,int
        int parameterCount = function.getValueParameterCount();
        if (parameterCount == 0 || parameterCount > 3) {
            return false;
        }

        List<UVariable> parameters = function.getValueParameters();
        if (!parameters.get(0).getType().matchesFqName(CLASS_CONTEXT)) {
            return false;
        }
        if (parameterCount == 1) {
            return true;
        }
        if (!parameters.get(1).getType().matchesFqName(CLASS_ATTRIBUTE_SET)) {
            return false;
        }
        //noinspection SimplifiableIfStatement
        if (parameterCount == 2) {
            return true;
        }
        return parameters.get(2).getType().isInt();
    }

    @Nullable
    @Override
    public List<String> applicableSuperClasses() {
        return Collections.singletonList(SdkConstants.CLASS_VIEW);
    }

    @Override
    public void checkClass(@NonNull JavaContext context, @NonNull UClass declaration) {
        // Only applies to concrete classes
        if (declaration.hasModifier(UastModifier.ABSTRACT) || declaration.isAnonymous()) {
            // Ignore abstract classes
            return;
        }

        if (UastUtils.getContainingClass(declaration) != null &&
                !declaration.hasModifier(UastModifier.STATIC)) {
            // Ignore inner classes that aren't static: we can't create these
            // anyway since we'd need the outer instance
            return;
        }

        boolean found = false;
        for (UFunction constructor : declaration.getConstructors()) {
            if (isXmlConstructor(constructor)) {
                found = true;
                break;
            }
        }

        if (!found) {
            String message = String.format(
                    "Custom view `%1$s` is missing constructor used by tools: "
                            + "`(Context)` or `(Context,AttributeSet)` "
                            + "or `(Context,AttributeSet,int)`",
                    declaration.getName());
            Location location = context.getLocation(declaration.getNameElement());
            context.report(ISSUE, declaration, location, message  /*data*/);
        }

    }
}
