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

package com.android.tools.internal.sdk.javalib

import com.android.tools.internal.sdk.base.PlatformConfig
import com.android.tools.internal.sdk.base.SdkFilesPlugin
import com.google.common.collect.Sets
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.tasks.bundling.Jar
import org.gradle.internal.reflect.Instantiator

import javax.inject.Inject

import static com.android.tools.internal.BaseTask.isAndroidArtifact
import static com.android.tools.internal.BaseTask.isAndroidExternalArtifact
import static com.android.tools.internal.BaseTask.isLocalArtifact

public class SdkJavaLibPlugin extends SdkFilesPlugin {

    private Jar buildTask
    private CopyDependenciesTask copyDepTask

    @Inject
    public SdkJavaLibPlugin(Instantiator instantiator) {
        super(instantiator)
    }

    @Override
    void apply(Project project) {
        super.apply(project)

        File sdkDir =  new File(project.buildDir, "sdk")

        // ----------
        // Task to build the jar files that goes in the tools folder.
        // This is called through the ToolItem.builtBy
        buildTask = project.tasks.create("sdkJar", Jar)
        buildTask.from(project.sourceSets.main.output)
        buildTask.conventionMapping.destinationDir = {
            new File(sdkDir, "jar")
        }
        buildTask.conventionMapping.archiveName = { project.archivesBaseName + ".jar" }

        // delay computing the manifest classpath only if the
        // prebuiltJar task is set to run.
        project.gradle.taskGraph.whenReady { taskGraph ->
            if (taskGraph.hasTask(project.tasks.sdkJar)) {
                project.tasks.sdkJar.manifest.attributes(
                        "Class-Path": getClassPath())
            }
        }

        // ----------
        // Task to gather the Jar dependencies
        // This is called through the ToolItem.builtBy
        copyDepTask = project.tasks.create("copyDependencies", CopyDependenciesTask)
        copyDepTask.outputDir = new File(sdkDir, "deps")
        copyDepTask.noticeDir = new File(sdkDir, "deps_notices")
        copyDepTask.repoDir = new File(project.rootProject.cloneArtifacts.repository)

        // ----------

    }

    @Override
    protected void createCopyTaskHook() {
        super.createCopyTaskHook()

        for (PlatformConfig platform : extension.getPlatforms()) {
            platform.item(buildTask.getArchivePath()) {
                into 'lib/'
                builtBy buildTask
            }

            platform.item(copyDepTask.outputDir) {
                into 'lib/'
                builtBy copyDepTask
                notice null
            }
        }
    }

    private String getClassPath() {
        StringBuilder sb = new StringBuilder()

        Configuration configuration = project.configurations.runtime
        getClassPathFromConfiguration(configuration, sb)

        return sb.toString()
    }

    protected void getClassPathFromConfiguration(Configuration configuration, StringBuilder sb) {
        // need to detect local files, so we first do a search by artifacts.
        Set<String> processedFiles = Sets.newHashSet()

        Set<ResolvedArtifact> artifacts = configuration.resolvedConfiguration.resolvedArtifacts
        for (ResolvedArtifact artifact : artifacts) {
            ModuleVersionIdentifier id = artifact.moduleVersion.id
            if ((isAndroidArtifact(id) && !isAndroidExternalArtifact(id)) || isLocalArtifact(id)) {
                // add the shorter name for the android dependencies
                sb.append(' ').append(artifact.moduleVersion.id.name + ".jar")
            } else {
                // add the full name
                sb.append(' ').append(artifact.file.name)
            }
            processedFiles << artifact.file.name
        }

        // for local file, go through the file list, and look at non processed files yet.
        for (File file : configuration.files) {
            String name = file.name
            if (processedFiles.contains(name)) {
                continue
            }
            String suffix = "-" + project.version + ".jar"
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.size() - suffix.size()) + ".jar"
            }
            sb.append(' ').append(name)
        }
    }

}
