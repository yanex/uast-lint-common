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

package com.android.tools.internal.artifacts.offline

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.bundling.Zip

/**
 * small plugin to setup task for creating offline repo.
 */
class OfflineRepoPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {

        project.ext.offlineRepo = new File(project.buildDir, 'intermediaries/repo')

        Task prepareOfflineRepo = project.tasks.create('prepareOfflineRepo')
        prepareOfflineRepo.doFirst {
            project.ext.offlineRepo.delete()
            project.ext.offlineRepo.mkdirs()
        }

        List<String> entryProjectPaths = [':base:gradle', ':base:gradle-experimental', ':dataBinding:compiler']
        /*
         * Identify all project and subprojects output artifacts and copy .jar and .pom
         * files into the repoDir local maven repository
         */
        Task copySubProjectsArtifacts = project.tasks.create('copySubProjectsArtifacts')
        copySubProjectsArtifacts.dependsOn prepareOfflineRepo
        copySubProjectsArtifacts.doFirst {

            // top project is the root of all the gradle plugin dependencies
            Set<Project> projectsToConsider = new HashSet<Project>()
            for (String projectPath: entryProjectPaths) {
                projectsToConsider.addAll(getAllDependencies(project, project.findProject(projectPath)))

            }
            // for each projects, check its output artifact and copy it only with the associated pom file to our
            // local maven repo.
            projectsToConsider.each { someProject ->
                someProject.configurations.runtime.artifacts.files.each { file ->
                    String relativePath = "${someProject.group.replace('.' as char, File.separatorChar)}${File.separatorChar}${someProject.name}${File.separatorChar}${someProject.version}"
                    File outDir = new File(project.ext.offlineRepo, relativePath)
                    File sourceDir = new File(new File(project.ext.localRepo), relativePath)
                    outDir.mkdirs()

                    project.copy {
                        from new File(sourceDir, file.name)
                        into outDir
                    }
                    project.copy {
                        from new File(sourceDir, file.name.replace(".jar", ".pom"))
                        into outDir
                    }
                }
            }
        }

        /**
         * Identify all transitive dependencies from the passed project and copy each jar and pom files into
         * a maven style repository located at outDir.
         */
        Task copyProjectDependencies = project.tasks.create('copyProjectDependencies', CopyProjectDependencyTask)
        copyProjectDependencies.dependsOn prepareOfflineRepo
        copyProjectDependencies.entryProjectPaths = entryProjectPaths

        Task makeOfflineRepo = project.tasks.create('makeOfflineRepo') {
            outputs.dir project.ext.offlineRepo
        }
        makeOfflineRepo.dependsOn project.tasks.publishLocal

        copyProjectDependencies.mustRunAfter 'publishLocal'
        makeOfflineRepo.dependsOn copyProjectDependencies
        copySubProjectsArtifacts.mustRunAfter 'publishLocal'
        makeOfflineRepo.dependsOn copySubProjectsArtifacts

        /**
         * Zip the maven style repository into a zip file.
         */
        Task zipOfflineRepo = project.tasks.create('zipOfflineRepo', Zip) {
            inputs.files makeOfflineRepo.outputs.files
            File outputFile = new File(project.ext.androidHostDist, 'offline_repo.zip')
            outputs.file outputFile
            from makeOfflineRepo.outputs.files
            archiveName outputFile.name
            destinationDir outputFile.parentFile
        }
        zipOfflineRepo.dependsOn makeOfflineRepo
    }

    private static Collection<Project> getAllDependencies(Project rootProject, Project topProject) {
        def projects = topProject.configurations.runtime.incoming.resolutionResult.allDependencies.findResults {
            (it.selected.id instanceof ProjectComponentIdentifier) ? rootProject.findProject(it.selected.id.projectPath) : null
        }

        projects.unique().add(topProject)
        return projects;
    }
}
