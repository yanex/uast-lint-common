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

import static com.android.SdkConstants.CLASS_PARCEL;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UastLiteralUtils;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Arrays;
import java.util.List;

/**
 * Looks for Parcelable classes that are missing a CREATOR field
 */
public class ReadParcelableDetector extends Detector implements Detector.UastScanner {

    /** The main issue discovered by this detector */
    public static final Issue ISSUE = Issue.create(
            "ParcelClassLoader", //$NON-NLS-1$
            "Default Parcel Class Loader",

            "The documentation for `Parcel#readParcelable(ClassLoader)` (and its variations) " +
            "says that you can pass in `null` to pick up the default class loader. However, " +
            "that ClassLoader is a system class loader and is not able to find classes in " +
            "your own application.\n" +
            "\n" +
            "If you are writing your own classes into the `Parcel` (not just SDK classes like " +
            "`String` and so on), then you should supply a `ClassLoader` for your application " +
            "instead; a simple way to obtain one is to just call `getClass().getClassLoader()` " +
            "from your own class.",

            Category.CORRECTNESS,
            3,
            Severity.WARNING,
            new Implementation(
                    ReadParcelableDetector.class,
                    Scope.JAVA_FILE_SCOPE))
            .addMoreInfo("http://developer.android.com/reference/android/os/Parcel.html");

    /** Constructs a new {@link ReadParcelableDetector} check */
    public ReadParcelableDetector() {
    }

    // ---- Implements JavaScanner ----

    @Override
    public List<String> getApplicableFunctionNames() {
        return Arrays.asList(
                "readParcelable",
                "readParcelableArray",
                "readBundle",
                "readArray",
                "readSparseArray",
                "readValue",
                "readPersistableBundle"
        );
    }

    @Override
    public void visitFunctionCallExpression(@NonNull JavaContext context,
            @Nullable UastVisitor visitor, @NonNull UCallExpression call,
            @NonNull UFunction function) {
        UClass containingClass = UastUtils.getContainingClass(function);
        if (containingClass == null) {
            return;
        }
        if (!(CLASS_PARCEL.equals(containingClass.getFqName()))) {
            return;
        }

        List<UExpression> arguments = call.getValueArguments();
        int argumentCount = arguments.size();
        if (argumentCount == 0) {
            String message = String.format("Using the default class loader "
                            + "will not work if you are restoring your own classes. Consider "
                            + "using for example `%1$s(getClass().getClassLoader())` instead.",
                    call.getFunctionName());
            context.report(ISSUE, call, context.getLocation(call), message);
        } else if (argumentCount == 1) {
            UExpression firstArg = arguments.get(0);
            if (UastLiteralUtils.isNullLiteral(firstArg)) {
                String message = "Passing null here (to use the default class loader) "
                        + "will not work if you are restoring your own classes. Consider "
                        + "using for example `getClass().getClassLoader()` instead.";
                context.report(ISSUE, call, context.getLocation(firstArg), message);
            }
        }

    }
}
