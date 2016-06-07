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

package com.android.tools.lint.detector.api;

import com.android.tools.lint.LintCoreEnvironment;
import com.intellij.core.JavaCoreProjectEnvironment;
import com.intellij.mock.MockProject;
import com.intellij.openapi.util.Disposer;

import junit.framework.TestCase;

public class TestCaseWithIntelliJCore extends TestCase {
    private MockProject mProject;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        LintCoreEnvironment lintEnvironment = new LintCoreEnvironment(Disposer.newDisposable());
        JavaCoreProjectEnvironment projectEnvironment = lintEnvironment.getProjectEnvironment();
        mProject = projectEnvironment.getProject();
    }

    protected MockProject getProject() {
        return mProject;
    }
}
