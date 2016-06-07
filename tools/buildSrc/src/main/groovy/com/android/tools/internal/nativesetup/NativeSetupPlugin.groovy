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
import org.gradle.nativeplatform.toolchain.Clang
import org.gradle.nativeplatform.toolchain.Gcc
/**
 */
class NativeSetupPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        String os = System.getProperty("os.name");

        project.model {
            platforms {
                linux {
                    architecture "i386"
                    operatingSystem "linux"
                }
                darwin {
                    architecture "i386"
                    operatingSystem "osx"
                }
            }
            toolChains {
                if (os.startsWith("Mac OS")) {
                    hostClang(Clang)
                } else if (os.startsWith("Linux")) {
                    hostGcc(Gcc)
                }
            }
        }

        project.apply plugin: 'windows-setup'
    }
}
