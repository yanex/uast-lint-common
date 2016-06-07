/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.tools.internal.artifacts

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException

class CloneArtifactsPlugin implements Plugin<Project> {

    final static String GRADLE_SNAPSHOT_REPO = 'https://repo.gradle.org/gradle/libs-snapshots-local';
    final static String GRADLE_RELEASES_REPO = "https://repo.gradle.org/gradle/libs-releases-local";

    @Override
    void apply(Project project) {
        // put some tasks on the project.
        Task cloneArtifacts = project.tasks.create("cloneArtifacts")
        cloneArtifacts.setDescription("Clone dependencies")

        // if this is the top project.
        if (project.rootProject == project) {
            def extension = project.extensions.create('cloneArtifacts', CloneArtifactsExtension)

            DownloadArtifactsTask downloadArtifactsTask = project.tasks.create("downloadArtifacts",
                    DownloadArtifactsTask)
            downloadArtifactsTask.project = project
            downloadArtifactsTask.conventionMapping.repository =  { project.file(extension.repository) }

            cloneArtifacts.dependsOn downloadArtifactsTask

            project.afterEvaluate {
                for (Project subProject : project.subprojects) {
                    try {
                        Task task = subProject.tasks.getByName("cloneArtifacts")
                        downloadArtifactsTask.dependsOn task
                    } catch (UnknownTaskException e) {
                        // ignore
                    }
                }
            }
        }
    }
}
