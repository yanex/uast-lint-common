/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tools.internal.sdk.base
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.internal.reflect.Instantiator

import javax.inject.Inject

/**
 */
class SdkFilesPlugin extends BaseSdkPlugin implements Plugin<Project> {

    private final Instantiator instantiator

    BaseExtension extension

    @Inject
    public SdkFilesPlugin(Instantiator instantiator) {
        this.instantiator = instantiator
    }

    @Override
    void apply(Project project) {
        super.apply(project)

        extension = project.extensions.create('sdk', BaseExtension, instantiator)

        project.afterEvaluate {
            createCopyTask()
        }
    }

    /**
     * Hooks called in afterEvaluate, before the ToolItem are queried. Allows delaying some work
     * in case we need some dynamic features (since ToolItem doesn't support closures)
     */
    protected void createCopyTaskHook() {
        // nothing here
    }

    protected createCopyTask() {
        createCopyTaskHook()

        for (PlatformConfig platform : extension.getPlatforms()) {

            CopyToolItemsTask copySdkToolsFiles = project.tasks.create(
                    "copy${platform.name.capitalize()}SdkToolsFiles", CopyToolItemsTask)

            copySdkToolsFiles.items = platform.items
            copySdkToolsFiles.itemOutputDir = new File(getSdkRoot(), platform.name + File.separatorChar + "tools")
            copySdkToolsFiles.noticeDir = new File(project.buildDir, "sdk/notices")

            copySdkToolsFiles.dependsOn platform.builtBy
        }
    }
}
