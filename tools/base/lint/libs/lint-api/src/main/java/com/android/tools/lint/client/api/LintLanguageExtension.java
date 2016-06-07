/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.tools.lint.client.api;

import com.android.annotations.Nullable;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;

import org.jetbrains.uast.UastContext;
import org.jetbrains.uast.UastConverter;
import org.jetbrains.uast.UastLanguagePlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class LintLanguageExtension implements UastLanguagePlugin {
    public static final ExtensionPointName<LintLanguageExtension> EP_NAME =
        ExtensionPointName.create("com.android.tools.klint.client.api.lintLanguageExtension");

    public static boolean isFileSupported(@Nullable Project project, String name) {
        if (project == null) {
            return false;
        }

        LintLanguageExtension[] extensions = project.getExtensions(EP_NAME);
        for (LintLanguageExtension ext : extensions) {
            if (ext.getConverter().isFileSupported(name)) {
                return true;
            }
        }

        return false;
    }

    public static List<UastLanguagePlugin> getPlugins(@Nullable Project project) {
        if (project == null) {
            return Collections.emptyList();
        }

        LintLanguageExtension[] extensions = project.getExtensions(EP_NAME);
        List<UastLanguagePlugin> languagePlugins =
                new ArrayList<UastLanguagePlugin>(extensions.length);
        Collections.addAll(languagePlugins, extensions);
        return languagePlugins;
    }
}
