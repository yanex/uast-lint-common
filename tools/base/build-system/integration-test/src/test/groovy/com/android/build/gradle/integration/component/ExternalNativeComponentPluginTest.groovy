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

package com.android.build.gradle.integration.component

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldJniApp
import com.android.builder.model.NativeAndroidProject
import com.android.builder.model.NativeArtifact
import com.android.builder.model.NativeSettings
import com.android.builder.model.NativeToolchain
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThat

/**
 * Test the ExternalNativeComponentModelPlugin.
 */
@Ignore
class ExternalNativeComponentPluginTest {

    @Rule
    public GradleTestProject project = GradleTestProject.builder()
            .fromTestApp(new HelloWorldJniApp())
            .forExperimentalPlugin(true)
            .create();

    @Test
    public void "check configurations using JSON data file"() {
        project.getBuildFile() << """
apply plugin: 'com.android.model.external'

model {
    nativeBuild {
        create {
            config "config.json"
        }
    }
}
"""
        project.file("config.json") << """
{
    "buildFiles" : ["CMakeLists.txt"],
    "libraries" : {
        "foo" : {
            "executable" : "touch",
            "args" : ["output.txt"],
            "toolchain" : "toolchain1",
            "output" : "build/libfoo.so",
            "folders" : [
                {
                    src : "src/main/jni",
                    cFlags : ["folderCFlag1", "folderCFlag2"],
                    cppFlags : ["folderCppFlag1", "folderCppFlag2"]
                }
            ],
            "files" : [
                {
                    "src" : "src/main/jni/hello.c",
                    "flags" : ["fileFlag1", "fileFlag2"]
                }
            ]
        }
    },
    "toolchains" : {
        "toolchain1" : {
            "cCompilerExecutable" : "clang",
            "cppCompilerExecutable" : "clang++"
        }
    },
    "cFileExtensions" : ["c"],
    "cppFileExtensions" : ["cpp"]
}
"""
        NativeAndroidProject model = project.executeAndReturnModel(NativeAndroidProject.class, "assemble")
        checkModel(model);
    }

    @Test
    public void "check configurations with multiple JSON data file"() {
        project.getBuildFile() << """
apply plugin: 'com.android.model.external'

model {
    nativeBuild {
        create {
            config "config1.json"
            commandString "./generate_config1.sh"
        }
        create {
            config "config2.json"
        }
    }
}
"""
        project.file("generate_config1.sh") << """echo '
{
    "buildFiles" : ["CMakeLists.txt"],
    "libraries" : {
        "foo" : {
            "executable" : "touch",
            "args" : ["foo.txt"],
            "toolchain" : "toolchain1",
            "output" : "build/libfoo.so"
        }
    },
    "toolchains" : {
        "toolchain1" : {
            "cCompilerExecutable" : "clang",
            "cppCompilerExecutable" : "clang++"
        }
    }
}' > config1.json
"""
        project.file("generate_config1.sh").setExecutable(true)

        project.file("config2.json") << """
{
    "buildFiles" : ["CMakeLists.txt"],
    "libraries" : {
        "bar" : {
            "executable" : "touch",
            "args" : ["bar.txt"],
            "toolchain" : "toolchain2",
            "output" : "build/libbar.so"
        }
    },
    "toolchains" : {
        "toolchain2" : {
            "cCompilerExecutable" : "gcc",
            "cppCompilerExecutable" : "g++"
        }
    }
}
"""
        assertThat(project.file("config1.json")).doesNotExist()
        project.execute("generateConfigfile")
        assertThat(project.file("config1.json")).exists()

        NativeAndroidProject model = project.executeAndReturnModel(NativeAndroidProject.class, "assemble")
        assertThat(model.getFileExtensions()).containsEntry("c", "c")
        assertThat(model.getFileExtensions()).containsEntry("C", "c++")
        assertThat(model.getFileExtensions()).containsEntry("CPP", "c++")
        assertThat(model.getFileExtensions()).containsEntry("c++", "c++")
        assertThat(model.getFileExtensions()).containsEntry("cc", "c++")
        assertThat(model.getFileExtensions()).containsEntry("cp", "c++")
        assertThat(model.getFileExtensions()).containsEntry("cpp", "c++")
        assertThat(model.getFileExtensions()).containsEntry("cxx", "c++")

        assertThat(model.artifacts).hasSize(2)
        for (NativeArtifact artifact : model.artifacts) {
            if (artifact.getName().equals("foo")) {
                assertThat(artifact.getName()).isEqualTo("foo")
                assertThat(artifact.getToolChain()).isEqualTo("toolchain1")
                assertThat(artifact.getOutputFile()).hasName("libfoo.so")
            } else {
                assertThat(artifact.getName()).isEqualTo("bar")
                assertThat(artifact.getToolChain()).isEqualTo("toolchain2")
                assertThat(artifact.getOutputFile()).hasName("libbar.so")
            }
        }

        assertThat(model.getToolChains()).hasSize(2)
        for (NativeToolchain toolchain : model.getToolChains()) {
            if (toolchain.getName().equals("toolchain1")) {

                assertThat(toolchain.getName()).isEqualTo("toolchain1")
                assertThat(toolchain.getCCompilerExecutable().getName()).isEqualTo("clang")
                assertThat(toolchain.getCppCompilerExecutable().getName()).isEqualTo("clang++")
            } else {
                assertThat(toolchain.getName()).isEqualTo("toolchain2")
                assertThat(toolchain.getCCompilerExecutable().getName()).isEqualTo("gcc")
                assertThat(toolchain.getCppCompilerExecutable().getName()).isEqualTo("g++")
            }
        }
    }

    @Test
    public void "check configurations using plugin DSL"() {
        project.getBuildFile() << """
apply plugin: 'com.android.model.external'

model {
    nativeBuildConfig {
        buildFiles.addAll([file("CMakeLists.txt")])
        CFileExtensions.add("c")
        cppFileExtensions.add("cpp")

        libraries {
            create("foo") {
                executable "touch"
                args.addAll(["output.txt"])
                toolchain "toolchain1"
                output file("build/libfoo.so")
                folders {
                    create() {
                        src "src/main/jni"
                        CFlags.addAll(["folderCFlag1", "folderCFlag2"])
                        cppFlags.addAll(["folderCppFlag1", "folderCppFlag2"])
                    }
                }
                files {
                    create() {
                        src "src/main/jni/hello.c"
                        flags.addAll(["fileFlag1", "fileFlag2"])
                    }
                }

            }
        }
        toolchains {
            create("toolchain1") {
                CCompilerExecutable = "clang"
                cppCompilerExecutable "clang++"

            }
        }
    }
}
"""
        NativeAndroidProject model = project.executeAndReturnModel(NativeAndroidProject.class, "assemble")
        checkModel(model);
    }

    private void checkModel(NativeAndroidProject model) {
        Collection<NativeSettings> settingsMap = model.getSettings();
        assertThat(project.file("output.txt")).exists()

        assertThat(model.artifacts).hasSize(1)

        assertThat(model.getFileExtensions()).containsEntry("c", "c")
        assertThat(model.getFileExtensions()).containsEntry("cpp", "c++")

        NativeArtifact artifact = model.artifacts.first()
        assertThat(artifact.getToolChain()).isEqualTo("toolchain1")
        assertThat(artifact.sourceFolders).hasSize(1)
        assertThat(artifact.sourceFolders.first().folderPath.toString()).endsWith("src/main/jni")
        NativeSettings setting = settingsMap.find { it.getName() == artifact.sourceFolders.first().perLanguageSettings.get("c") }
        assertThat(setting.getCompilerFlags()).containsAllOf("folderCFlag1", "folderCFlag2");
        setting = settingsMap.find { it.getName() == artifact.sourceFolders.first().perLanguageSettings.get("c++") }
        assertThat(setting.getCompilerFlags()).containsAllOf("folderCppFlag1", "folderCppFlag2");

        assertThat(artifact.sourceFiles).hasSize(1)
        assertThat(artifact.sourceFiles.first().filePath.toString()).endsWith("src/main/jni/hello.c")
        setting = settingsMap.find { it.getName() == artifact.sourceFiles.first().settingsName }
        assertThat(setting.getCompilerFlags()).containsAllOf("fileFlag1", "fileFlag2");

        assertThat(model.getToolChains()).hasSize(1)
        NativeToolchain toolchain = model.getToolChains().first()
        assertThat(toolchain.getName()).isEqualTo("toolchain1")
        assertThat(toolchain.getCCompilerExecutable().getName()).isEqualTo("clang")
        assertThat(toolchain.getCppCompilerExecutable().getName()).isEqualTo("clang++")
    }
}
