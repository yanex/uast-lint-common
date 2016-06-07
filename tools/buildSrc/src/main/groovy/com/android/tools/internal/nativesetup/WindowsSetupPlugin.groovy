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

package com.android.tools.internal.nativesetup
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.nativeplatform.toolchain.Gcc
/**
 */
class WindowsSetupPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.model {
            platforms {
                windows32 {
                    architecture "i386"
                    operatingSystem "windows"
                }
                windows64 {
                    architecture "i386"
                    operatingSystem "windows"
                }
            }

            toolChains {
                mingw(Gcc) {
                    path "$project.rootDir/../prebuilts/gcc/linux-x86/host/x86_64-w64-mingw32-4.8/bin"

                    target("windows32") {
                        cppCompiler.executable =  'x86_64-w64-mingw32-g++'
                        cppCompiler.withArguments { args ->
                            args.addAll '-DUSE_MINGW', '-D__STDC_FORMAT_MACROS', '-D__STDC_CONSTANT_MACROS', '-D__USE_MINGW_ANSI_STDIO', '-m32'
                        }

                        cCompiler.executable = 'x86_64-w64-mingw32-gcc'
                        cCompiler.withArguments { args ->
                            args.addAll '-DUSE_MINGW', '-D__STDC_FORMAT_MACROS', '-D__STDC_CONSTANT_MACROS', '-D__USE_MINGW_ANSI_STDIO', '-m32'
                        }

                        linker.executable = 'x86_64-w64-mingw32-g++'
                        linker.withArguments { args ->
                            args << '-m32'
                        }
                        assembler.executable = 'x86_64-w64-mingw32-as'
                        staticLibArchiver.executable = 'x86_64-w64-mingw32-ar'
                    }
                    target("windows64") {
                        cppCompiler.executable =  'x86_64-w64-mingw32-g++'
                        cppCompiler.withArguments { args ->
                            args.addAll '-DUSE_MINGW', '-D__STDC_FORMAT_MACROS', '-D__STDC_CONSTANT_MACROS', '-D__USE_MINGW_ANSI_STDIO'
                        }

                        cCompiler.executable = 'x86_64-w64-mingw32-gcc'
                        cCompiler.withArguments { args ->
                            args.addAll '-DUSE_MINGW', '-D__STDC_FORMAT_MACROS', '-D__STDC_CONSTANT_MACROS', '-D__USE_MINGW_ANSI_STDIO'
                        }

                        linker.executable = 'x86_64-w64-mingw32-g++'
                        linker.withArguments { args ->
                        }
                        assembler.executable = 'x86_64-w64-mingw32-as'
                        staticLibArchiver.executable = 'x86_64-w64-mingw32-ar'
                    }
                }
            }
        }

        project.extensions.create("windows", WindowsExtension, project)
    }

    public static class WindowsExtension {
        Project project

        public WindowsExtension(Project project) {
            this.project = project
        }

        public WindResTask createTask(String taskName,
                String rcPath,
                String imageFolderPath,
                String objName) {

            WindResTask task = project.tasks.create(taskName, WindResTask)

            task.winResExe = project.file("$project.rootDir/../prebuilts/gcc/linux-x86/host/x86_64-w64-mingw32-4.8/bin/x86_64-w64-mingw32-windres")
            task.rcFile = project.file(rcPath)
            task.imageFolder = project.file(imageFolderPath)
            task.objFile = project.file("$project.buildDir/windres/$objName")

            return task
        }
    }
}
