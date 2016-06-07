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

package com.android.build.gradle.integration.automatic;

import static com.google.common.base.Preconditions.checkState;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.runner.ParallelParameterized;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Test case that executes "standard" gradle tasks in all our tests projects.
 *
 * <p>You can run only one test like this:
 * <p>{@code gw :b:i-t:automaticTest --tests=*.CheckAll.lint[abiPureSplits]}
 */
@RunWith(ParallelParameterized.class)
public class CheckAll {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        List<Object[]> parameters = Lists.newArrayList();

        File[] testProjects = GradleTestProject.TEST_PROJECT_DIR.listFiles();
        checkState(testProjects != null);

        for (File testProject : testProjects) {
            if (!isValidProjectDirectory(testProject)) {
                continue;
            }

            parameters.add(new Object[]{testProject.getName()});
        }

        return parameters;
    }

    private static boolean isValidProjectDirectory(File testProject) {
        if (!testProject.isDirectory()) {
            return false;
        }

        File buildGradle = new File(testProject, "build.gradle");
        File settingsGradle = new File(testProject, "settings.gradle");

        return buildGradle.exists() || settingsGradle.exists();
    }

    @Rule
    public GradleTestProject project;

    public String projectName;

    public CheckAll(String projectName) {
        this.projectName = projectName;
        this.project = GradleTestProject.builder()
                .fromTestProject(projectName)
                .forExperimentalPlugin(COMPONENT_MODEL_PROJECTS.contains(projectName))
                .create();
    }

    @Test
    public void lint() throws Exception {
        Assume.assumeFalse(BROKEN_ASSEMBLE.contains(projectName));
        project.execute("lint");
    }

    @Test
    public void assemble() throws Exception {
        Assume.assumeFalse(BROKEN_ASSEMBLE.contains(projectName));
        project.execute("assembleDebug", "assembleAndroidTest");
    }

    // TODO: Investigate and clear these lists.
    private static final ImmutableSet<String> BROKEN_ASSEMBLE = ImmutableSet.of(
            "ndkRsHelloCompute", // TODO: Fails in C++ code, not sure what the issue is.

            // These are all right:
            "daggerTwo", // requires Java 7
            "projectWithLocalDeps", // Doesn't have a build.gradle, not much to check anyway.
            "duplicateNameImport", // Fails on purpose.
            "instant-unit-tests", // Specific to testing instant run, not a "real" project.
            "simpleManifestMergingTask" // Not an Android project.
    );

    private static final ImmutableSet<String> COMPONENT_MODEL_PROJECTS = ImmutableSet.of(
            "componentModel",
            "ndkSanAngeles2",
            "ndkVariants");
}
