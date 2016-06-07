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

import com.google.common.collect.Lists
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Zip

/**
 * Plugin for the root project. This orchestrates the output of all the modules
 * into the SDK Tools package
 */
public class SdkToolsPlugin extends BaseSdkPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        if (!project.equals(project.rootProject)) {
            throw new RuntimeException("sdk-tools plugin must be applied to root project only")
        }

        super.apply(project)

        Task makeTask = project.tasks.create("makeSdk")

        // prepare folders per platforms
        Task makeLinuxTask = setupPlatform("linux", "linux")
        Task makeMacTask = setupPlatform("mac","darwin")
        Task makeWinTask = setupPlatform("win", "windows")

        String os = System.getProperty("os.name");
        if (os.startsWith("Mac OS")) {
            makeTask.dependsOn makeMacTask
        } else if (os.startsWith("Windows")) {
            makeTask.dependsOn makeWinTask
        } else if (os.startsWith("Linux")) {
            makeTask.dependsOn makeLinuxTask
        }
    }

    private Task setupPlatform(String platformName, String plaformPkgName) {
        File root = new File(getSdkRoot(), platformName);

        File sdkRoot = new File(root, "tools")

        Task makeTask = project.tasks.create("make${platformName.capitalize()}Sdk")

        Task cleanFolder = project.tasks.create("clean${platformName.capitalize()}Sdk")
        cleanFolder.doFirst {
            sdkRoot.deleteDir()
            sdkRoot.mkdirs()
        }

        final String copyTaskName = "copy${platformName.capitalize()}SdkToolsFiles"
        String[] noticeTaskNames = [copyTaskName, "copyDependencies" ]

        Task copyFiles = project.tasks.create("copy${platformName.capitalize()}Sdk", MergeNoticesTask)
        copyFiles.noticeFile = new File(sdkRoot, "NOTICE.txt")
        copyFiles.noticeTaskNames = noticeTaskNames
        copyFiles.mustRunAfter cleanFolder

        Zip zipFiles = project.tasks.create("zip${platformName.capitalize()}Sdk", Zip)
        zipFiles.from(sdkRoot)
        zipFiles.destinationDir = project.ext.androidHostDist

        String buildNumber = System.getenv("BUILD_NUMBER")
        String zipName
        if (buildNumber == null) {
            zipName = "sdk-repo-$plaformPkgName-tools.zip"
        } else {
            zipName = "sdk-repo-$plaformPkgName-tools-${buildNumber}.zip"
        }

        zipFiles.setArchiveName(zipName)
        zipFiles.mustRunAfter copyFiles

        makeTask.description = "Packages the ${platformName.capitalize()} SDK Tools"
        makeTask.group = "Android SDK"
        makeTask.dependsOn cleanFolder, copyFiles, zipFiles

        project.afterEvaluate {
            List<Task> copyTasks = Lists.newArrayList()

            project.subprojects.each { p ->
                NamedDomainObjectSet<Task> matches = p.tasks.matching { t ->
                    copyTaskName.equals(t.name)
                }
                copyTasks.addAll(matches)
            }

            copyFiles.dependsOn copyTasks
        }

        return makeTask
    }
}
