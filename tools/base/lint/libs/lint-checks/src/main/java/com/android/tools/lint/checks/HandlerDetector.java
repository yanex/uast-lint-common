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

import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UFunction;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastModifier;
import org.jetbrains.uast.UastUtils;
import org.jetbrains.uast.util.UastExpressionUtils;

import java.util.Collections;
import java.util.List;

/**
 * Checks that Handler implementations are top level classes or static.
 * See the corresponding check in the android.os.Handler source code.
 */
public class HandlerDetector extends Detector implements Detector.UastScanner {

    /** Potentially leaking handlers */
    public static final Issue ISSUE = Issue.create(
            "HandlerLeak", //$NON-NLS-1$
            "Handler reference leaks",

            "Since this Handler is declared as an inner class, it may prevent the outer " +
            "class from being garbage collected. If the Handler is using a Looper or " +
            "MessageQueue for a thread other than the main thread, then there is no issue. " +
            "If the Handler is using the Looper or MessageQueue of the main thread, you " +
            "need to fix your Handler declaration, as follows: Declare the Handler as a " +
            "static class; In the outer class, instantiate a WeakReference to the outer " +
            "class and pass this object to your Handler when you instantiate the Handler; " +
            "Make all references to members of the outer class using the WeakReference object.",

            Category.PERFORMANCE,
            4,
            Severity.WARNING,
            new Implementation(
                    HandlerDetector.class,
                    Scope.JAVA_FILE_SCOPE));

    private static final String LOOPER_CLS = "android.os.Looper";
    private static final String HANDLER_CLS = "android.os.Handler";

    /** Constructs a new {@link HandlerDetector} */
    public HandlerDetector() {
    }

    // ---- Implements UastScanner ----

    @Nullable
    @Override
    public List<String> applicableSuperClasses() {
        return Collections.singletonList(HANDLER_CLS);
    }

    @Override
    public void checkClass(@NonNull JavaContext context, @NonNull UClass declaration) {
        // Only consider static inner classes
        if (UastUtils.isTopLevel(declaration) || declaration.hasModifier(UastModifier.STATIC)) {
            return;
        }

        //// Only flag handlers using the default looper
        //noinspection unchecked
        UCallExpression invocation = UastUtils.getParentOfType(declaration, UCallExpression.class,
                true, UFunction.class);
        if (invocation != null && UastExpressionUtils.isConstructorCall(invocation)) {
            if (hasLooperConstructorParameter(declaration)) {
                // This is an inner class which takes a Looper parameter:
                // possibly used correctly from elsewhere
                return;
            }

            UElement locationNode = declaration.getNameElement();
            Location location = context.getLocation(locationNode);
            String name = declaration.getFqName();

            if (declaration.isAnonymous()) {
                UClass superClass = declaration.getSuperClass(context);
                if (superClass != null) {
                    name = "anonymous " + superClass.getFqName();
                }
            }

            //noinspection VariableNotUsedInsideIf
            context.report(ISSUE, locationNode, location, String.format(
                    "This Handler class should be static or leaks might occur (%1$s)",
                    name));
        }
    }

    private static boolean hasLooperConstructorParameter(@NonNull UClass cls) {
        for (UFunction constructor : cls.getConstructors()) {
            for (UVariable parameter : constructor.getValueParameters()) {
                UType type = parameter.getType();
                if (type.matchesFqName(LOOPER_CLS)) {
                    return true;
                }
            }
        }
        return false;
    }
}
