/*
 * Copyright (C) 2011 The Android Open Source Project
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
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Location;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UType;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.Collections;
import java.util.List;

/**
 * Looks for hardcoded references to /sdcard/.
 */
public class SdCardDetector extends Detector implements Detector.UastScanner {
    /** Hardcoded /sdcard/ references */
    public static final Issue ISSUE = Issue.create(
            "SdCardPath", //$NON-NLS-1$
            "Hardcoded reference to `/sdcard`",

            "Your code should not reference the `/sdcard` path directly; instead use " +
            "`Environment.getExternalStorageDirectory().getPath()`.\n" +
            "\n" +
            "Similarly, do not reference the `/data/data/` path directly; it can vary " +
            "in multi-user scenarios. Instead, use " +
            "`Context.getFilesDir().getPath()`.",

            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            new Implementation(
                    SdCardDetector.class,
                    Scope.JAVA_FILE_SCOPE))
            .addMoreInfo(
            "http://developer.android.com/guide/topics/data/data-storage.html#filesExternal"); //$NON-NLS-1$

    /** Constructs a new {@link SdCardDetector} check */
    public SdCardDetector() {
    }


    // ---- Implements JavaScanner ----

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return Collections.<Class<? extends UElement>>singletonList(ULiteralExpression.class);
    }

    @Override
    public UastVisitor createUastVisitor(@NonNull JavaContext context) {
        return new StringChecker(context);
    }

    private static class StringChecker extends AbstractUastVisitor {
        private final JavaContext mContext;

        public StringChecker(JavaContext context) {
            mContext = context;
        }

        @Override
        public boolean visitLiteralExpression(@NotNull ULiteralExpression node) {
            UType type = node.getExpressionType();
            if (type != null && type.isString()) {
                String s = (String)node.getValue();
                if (s == null || s.isEmpty()) {
                    return super.visitLiteralExpression(node);
                }
                char c = s.charAt(0);
                if (c != '/' && c != 'f') {
                    return super.visitLiteralExpression(node);
                }

                if (s.startsWith("/sdcard")                        //$NON-NLS-1$
                        || s.startsWith("/mnt/sdcard/")            //$NON-NLS-1$
                        || s.startsWith("/system/media/sdcard")    //$NON-NLS-1$
                        || s.startsWith("file://sdcard/")          //$NON-NLS-1$
                        || s.startsWith("file:///sdcard/")) {      //$NON-NLS-1$
                    String message = "Do not hardcode \"/sdcard/\"; " +
                            "use `Environment.getExternalStorageDirectory().getPath()` instead";
                    Location location = mContext.getLocation(node);
                    mContext.report(ISSUE, node, location, message);
                } else if (s.startsWith("/data/data/")    //$NON-NLS-1$
                        || s.startsWith("/data/user/")) { //$NON-NLS-1$
                    String message = "Do not hardcode \"`/data/`\"; " +
                            "use `Context.getFilesDir().getPath()` instead";
                    Location location = mContext.getLocation(node);
                    mContext.report(ISSUE, node, location, message);
                }
            }

            return super.visitLiteralExpression(node);
        }
    }
}
