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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UastConverter;
import org.jetbrains.uast.UastVisitorExtension;
import org.jetbrains.uast.java.JavaUastLanguagePlugin;

import java.util.List;

public class JavaLintLanguageExtension extends LintLanguageExtension {
    @NotNull
    @Override
    public UastConverter getConverter() {
        return JavaUastLanguagePlugin.getINSTANCE().getConverter();
    }

    @NotNull
    @Override
    public List<UastVisitorExtension> getVisitorExtensions() {
        return JavaUastLanguagePlugin.getINSTANCE().getVisitorExtensions();
    }
}
