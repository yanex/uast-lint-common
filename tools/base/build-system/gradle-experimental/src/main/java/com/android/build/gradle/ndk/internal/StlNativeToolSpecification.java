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

package com.android.build.gradle.ndk.internal;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.NdkHandler;
import com.android.build.gradle.internal.core.Abi;
import com.google.common.collect.Lists;

import org.gradle.nativeplatform.platform.NativePlatform;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Compiler flags to configure STL.
 */
public class StlNativeToolSpecification extends AbstractNativeToolSpecification {
    private NdkHandler ndkHandler;
    private String stl;
    private String stlName;
    private String stlVersion;
    private Boolean isStatic;
    private NativePlatform platform;

    public StlNativeToolSpecification(
            @NonNull NdkHandler ndkHandler,
            @NonNull String stl,
            @Nullable String stlVersion,
            @NonNull NativePlatform platform) {
        this.ndkHandler = ndkHandler;
        this.stl = stl;
        this.stlVersion = stlVersion;
        this.stlName = stl.equals("system") ? stl : stl.substring(0, stl.indexOf('_'));
        this.isStatic = stl.endsWith("_static");
        this.platform = platform;
    }


    @Override
    public Iterable<String> getCFlags() {
        return Collections.emptyList();
    }

    @Override
    public Iterable<String> getCppFlags() {

        List<String> cppFlags = Lists.newArrayList();

        if (stlName.equals("c++")) {
            cppFlags.add("-std=c++11");
        }

        List<File> includeDirs = ndkHandler.getStlIncludes(
                stlName,
                stlVersion,
                Abi.getByName(platform.getName()));
        for (File dir : includeDirs) {
            cppFlags.add("-I" + dir.toString());
        }
        return cppFlags;
    }

    @Override
    public Iterable<String> getLdFlags() {
        if (stl.equals("system")) {
            return Collections.emptyList();
        }
        List<String> flags = Lists.newArrayList();
        File stlLib = getStlLib(platform.getName());
        // Add folder containing the STL library to ld library path so that user can easily append
        // STL with the -l flag.
        flags.add("-L" + stlLib.getParent());
        flags.add("-l" + stlLib.getName().substring(3, stlLib.getName().lastIndexOf('.')));
        return flags;
    }

    public File getStlLib(String abi) {
        String stlLib;
        if (stlName.equals("stlport")) {
            stlLib = "stlport";
        } else if (stlName.equals("gnustl")) {
            String version = stlVersion != null
                    ? stlVersion
                    : ndkHandler.getGccToolchainVersion(Abi.getByName(abi));
            stlLib = "gnu-libstdc++/" + version;
        } else if (stlName.equals("gabi++")) {
            stlLib = "gabi++";
        } else if (stlName.equals("c++")) {
            stlLib = "llvm-libc++";
        } else {
            throw new AssertionError(
                    "Unreachable.  Either stl is invalid or stl is \"system\", " +
                    "in which case there is no library file and getStlLib should not be called.");
        }
        return new File(
                StlConfiguration.getStlBaseDirectory(ndkHandler),
                stlLib + "/libs/" + platform.getName() + "/lib" + stl + (isStatic ? ".a" : ".so"));
    }
}
