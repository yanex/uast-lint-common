/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.internal.license;

import com.android.tools.internal.artifacts.PomHandler;
import com.android.tools.internal.artifacts.PomHandler.License;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Tasks outputting the license information for the external dependencies.
 *
 * It queries the 'runtime' configuration object, and fails if it doesn't find pom
 * file associated with the dependencies.
 *
 * This will NOT work with local jars.
 */
public class ReportTask extends DefaultTask {

    @OutputFile
    public File getOutputFile() {
        return new File(
                (File) getProject().getRootProject().getExtensions().getExtraProperties().get("androidHostDist"),
                "license-" + getProject().getName() + ".txt");
    }

    @TaskAction
    public void report() throws IOException {
        Project project = getProject();

        Configuration runtimeConfig = project.getConfigurations().getByName("runtime");

        Map<ModuleVersionIdentifier, List<ResolvedArtifact>> artifacts = collectArtifacts(runtimeConfig);

        Set<? extends DependencyResult> dependencyResultSet = runtimeConfig
                .getIncoming().getResolutionResult().getRoot().getDependencies();

        Set<File> pomFiles = new HashSet<File>();

        for (DependencyResult dependencyResult : dependencyResultSet) {
            if (dependencyResult instanceof ResolvedDependencyResult) {
                processConfig(
                        ((ResolvedDependencyResult) dependencyResult).getSelected(),
                        artifacts,
                        pomFiles);
            }
        }

        Map<String, List<License>> map = new HashMap<String, List<License>>(pomFiles.size());

        for (File pomFile : pomFiles) {
            PomHandler pomHandler = new PomHandler(pomFile);

            ModuleVersionIdentifier artifactName = pomHandler.getArtifactId();

            List<License> licenses = pomHandler.getLicenses();

            File parentPomFile = pomFile;
            PomHandler parentPomHandler = pomHandler;
            while (licenses.isEmpty()) {
                // get the parent pom
                parentPomFile = computeParentPomLocation(parentPomFile, parentPomHandler);
                if (parentPomFile == null) {
                    break;
                }
                parentPomHandler = new PomHandler(parentPomFile);
                licenses = parentPomHandler.getLicenses();
            }

            if (!licenses.isEmpty()) {
                map.put(artifactName.toString(), licenses);
            } else {
                throw new RuntimeException("unable to find license info for " + artifactName);
            }
        }

        List<String> keys = new ArrayList<String>(map.keySet());
        Collections.sort(keys);

        File outputFile = getOutputFile();
        FileWriter writer = new FileWriter(outputFile);
        try {
            for (String key : keys) {
                writer.write(key);
                writer.write("\n");
                for (License license : map.get(key)) {
                    writer.write("  > " + license.getName());
                    writer.write("\n");
                    if (license.getUrl() != null) {
                        writer.write("    " + license.getUrl());
                        writer.write("\n");
                    }
                    if (license.getComments() != null) {
                        writer.write("    " + license.getComments());
                        writer.write("\n");
                    }
                }
            }
        } finally {
            writer.close();
        }
    }

    private static File computeParentPomLocation(File pomFile, PomHandler pomHandler) throws IOException {
        // get the parent pom coordinate
        ModuleVersionIdentifier parentPomCoord = pomHandler.getParentPom();
        if (parentPomCoord == null) {
            return null;
        }

        // To find the location of the parentPom, we can rely on the following location pattern for pom files:
        // groupIdSeg1/groupIdSeg2/.../name/version/name-version.pom
        // So first we back track from the current pom to find the root of the repo

        // first remove the pom file, the version and the name:
        File parentPomFile = pomFile.getParentFile().getParentFile().getParentFile();

        // now get the number of groupId segment
        Iterable<String> segments = Splitter.on('.').split(pomHandler.getArtifactId().getGroup());
        //noinspection unused
        for (String segment : segments) {
            parentPomFile = parentPomFile.getParentFile();
        }

        // add the segments
        segments = Splitter.on('.').split(parentPomCoord.getGroup());
        for (String segment : segments) {
            parentPomFile = new File(parentPomFile, segment);
        }

        // add the name, version
        String name = parentPomCoord.getName();
        parentPomFile = new File(parentPomFile, name);
        String version = parentPomCoord.getVersion();
        parentPomFile = new File(parentPomFile, version);

        // add the pom filename
        parentPomFile = new File(parentPomFile, name + "-" + version + ".pom");
        return parentPomFile;
    }

    private static Map<ModuleVersionIdentifier, List<ResolvedArtifact>> collectArtifacts(Configuration configuration) {
        Map<ModuleVersionIdentifier, List<ResolvedArtifact>> artifacts = new HashMap<ModuleVersionIdentifier, List<ResolvedArtifact>>();

        Set<ResolvedArtifact> allArtifacts = configuration.getResolvedConfiguration().getResolvedArtifacts();

        for (ResolvedArtifact artifact : allArtifacts) {
            ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
            List<ResolvedArtifact> moduleArtifacts = artifacts.get(id);

            if (moduleArtifacts == null) {
                moduleArtifacts = Lists.newArrayList();
                artifacts.put(id, moduleArtifacts);
            }

            if (!moduleArtifacts.contains(artifact)) {
                moduleArtifacts.add(artifact);
            }
        }

        return artifacts;
    }

    private static void processConfig(
            ResolvedComponentResult resolvedComponentResult,
            Map<ModuleVersionIdentifier, List<ResolvedArtifact>> artifacts,
            Set<File> pomFiles) {

        ModuleVersionIdentifier moduleVersion = resolvedComponentResult.getModuleVersion();

        ComponentIdentifier id = resolvedComponentResult.getId();
        if (!(id instanceof ProjectComponentIdentifier)) {

            List<ResolvedArtifact> moduleArtifacts = artifacts.get(moduleVersion);

            if (moduleArtifacts != null) {
                for (ResolvedArtifact artifact : moduleArtifacts) {
                    File artifactFile = artifact.getFile();
                    String filename = artifactFile.getName().replaceAll(".jar$", ".pom").replaceAll(".aar$", ".pom");

                    // rename the file to get the pom.
                    File pomFile = new File(artifactFile.getParentFile(), filename);

                    if (!pomFile.exists()) {
                        throw new RuntimeException("Missing Pom file for artifact: " + artifactFile);
                    }

                    pomFiles.add(pomFile);
                }
            }
        }

        // then recursively
        Set<? extends DependencyResult> dependencies = resolvedComponentResult.getDependencies();
        for (DependencyResult dependencyResult : dependencies) {
            if (dependencyResult instanceof ResolvedDependencyResult) {
                processConfig(
                        ((ResolvedDependencyResult) dependencyResult).getSelected(),
                        artifacts,
                        pomFiles);
            }
        }
    }
}
