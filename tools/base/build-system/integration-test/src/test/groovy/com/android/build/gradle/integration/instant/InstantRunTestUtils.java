/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.build.gradle.integration.instant;

import static com.android.build.gradle.integration.common.utils.DeviceHelper.DEFAULT_ADB_TIMEOUT_MSEC;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertNotNull;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.OptionalCompilationStep;
import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.truth.TruthHelper;
import com.android.build.gradle.internal.incremental.ColdswapMode;
import com.android.builder.model.AndroidProject;
import com.android.builder.model.InstantRun;
import com.android.builder.model.Variant;
import com.android.builder.testing.api.DeviceException;
import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.resources.Density;
import com.android.sdklib.AndroidVersion;
import com.android.tools.fd.client.InstantRunArtifact;
import com.android.tools.fd.client.InstantRunArtifactType;
import com.android.tools.fd.client.InstantRunBuildInfo;
import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

public final class InstantRunTestUtils {

    @NonNull
    public static InstantRunBuildInfo loadContext(@NonNull InstantRun instantRunModel)
            throws Exception {
        InstantRunBuildInfo context = InstantRunBuildInfo.get(
                Files.toString(instantRunModel.getInfoFile(), Charsets.UTF_8));
        assertNotNull(context);
        return context;
    }

    @NonNull
    public static InstantRun getInstantRunModel(@NonNull AndroidProject project) {
        Collection<Variant> variants = project.getVariants();
        for (Variant variant : variants) {
            if ("debug".equals(variant.getName())) {
                return variant.getMainArtifact().getInstantRun();
            }
        }
        throw new AssertionError("Could not find debug variant.");
    }

    @NonNull
    public static List<String> getInstantRunArgs(int apiLevel,
            @NonNull ColdswapMode coldswapMode,
            @NonNull OptionalCompilationStep... flags) {
        return getInstantRunArgs(new AndroidVersion(apiLevel, null),
                null /* density */, coldswapMode, flags);
    }

    static List<String> getInstantRunArgs(
            @NonNull IDevice device,
            @NonNull ColdswapMode coldswapMode,
            @NonNull OptionalCompilationStep... flags) {
        return getInstantRunArgs(device.getVersion(),
                Density.getEnum(device.getDensity()), coldswapMode, flags);
    }

    @NonNull
    private static List<String> getInstantRunArgs(
            @Nullable AndroidVersion androidVersion,
            @Nullable Density denisty,
            @NonNull ColdswapMode coldswapMode,
            @NonNull OptionalCompilationStep[] flags) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        if (androidVersion != null) {
            builder.add(String.format(
                    "-Pandroid.injected.build.api=%s", androidVersion.getApiString()));
        }
        if (denisty != null) {
            builder.add(String.format(
                    "-Pandroid.injected.build.density=%s", denisty.getResourceValue()));
        }

        builder.add(String.format("-Pandroid.injected.coldswap.mode=%s", coldswapMode.name()));

        StringBuilder optionalSteps = new StringBuilder()
                .append("-P").append("android.optional.compilation").append('=')
                .append("INSTANT_DEV");
        for (OptionalCompilationStep step : flags) {
            optionalSteps.append(',').append(step);
        }
        builder.add(optionalSteps.toString());
        return builder.build();
    }

    static void doInstall(
            @NonNull IDevice device,
            @NonNull List<InstantRunArtifact> artifacts) throws DeviceException,
            InstallException {
        List<File> apkFiles = Lists.newArrayList();
        for (InstantRunArtifact artifact : artifacts) {
            if (artifact.type == InstantRunArtifactType.SPLIT) {
                apkFiles.add(artifact.file);
            }
            if (artifact.type == InstantRunArtifactType.MAIN ||
                    artifact.type == InstantRunArtifactType.SPLIT_MAIN ) {
                apkFiles.add(0, artifact.file);
            }
        }

        if (device.getVersion().isGreaterOrEqualThan(21)) {
            device.installPackages(apkFiles, true /*reinstall*/, ImmutableList.<String>of(),
                    DEFAULT_ADB_TIMEOUT_MSEC, MILLISECONDS);

        } else {
            assertThat(apkFiles).hasSize(1);
            device.installPackage(
                    Iterables.getOnlyElement(apkFiles).getAbsolutePath(), true /*reinstall*/);
        }
    }

    static void runApp(IDevice device, String target) throws Exception {
        IShellOutputReceiver receiver = new CollectingOutputReceiver();
        String command = "am start" +
                " -n " + target +
                " -a android.intent.action.MAIN" +
                " -c android.intent.category.LAUNCHER";
        device.executeShellCommand(
                command, receiver, DEFAULT_ADB_TIMEOUT_MSEC, MILLISECONDS);
    }

    static void stopApp(@NonNull IDevice device, @NonNull String target) throws Exception {
        IShellOutputReceiver receiver = new CollectingOutputReceiver();
        String command = "am start" +
                " -n " + target +
                " -a android.intent.action.MAIN" +
                " -c android.intent.category.LAUNCHER";
        device.executeShellCommand(
                command, receiver, DEFAULT_ADB_TIMEOUT_MSEC, MILLISECONDS);
    }

    static void unlockDevice(@NonNull IDevice device) throws Exception {
        IShellOutputReceiver receiver = new CollectingOutputReceiver();
        device.executeShellCommand(
                "input keyevent KEYCODE_WAKEUP", receiver,
                DEFAULT_ADB_TIMEOUT_MSEC, MILLISECONDS);
        device.executeShellCommand(
                "wm dismiss-keyguard", receiver,
                DEFAULT_ADB_TIMEOUT_MSEC, MILLISECONDS);
    }

    @NonNull
    static InstantRun doInitialBuild(
            @NonNull GradleTestProject project,
            int apiLevel,
            @NonNull ColdswapMode coldswapMode) {
        project.execute("clean");
        InstantRun instantRunModel = getInstantRunModel(project.getSingleModel());

        project.execute(
                getInstantRunArgs(apiLevel, coldswapMode, OptionalCompilationStep.RESTART_ONLY),
                "assembleDebug");

        return instantRunModel;
    }

    /**
     * Gets the {@link InstantRunArtifact} produced by last build.
     */
    @NonNull
    static InstantRunArtifact getCompiledHotSwapCompatibleChange(
            @NonNull InstantRun instantRunModel) throws Exception {
        InstantRunBuildInfo context = loadContext(instantRunModel);

        TruthHelper.assertThat(context.getArtifacts()).hasSize(1);
        InstantRunArtifact artifact = Iterables.getOnlyElement(context.getArtifacts());

        TruthHelper.assertThat(artifact.type).isEqualTo(InstantRunArtifactType.RELOAD_DEX);
        return artifact;
    }

    public static void printBuildInfoFile(@Nullable InstantRun instantRunModel) {
        if (instantRunModel == null) {
            System.err.println("Cannot print build info file as model is null");
            return;
        }
        try {
            System.out.println("------------ build info file ------------\n"
                    + Files.toString(instantRunModel.getInfoFile(), Charsets.UTF_8)
                    + "---------- end build info file ----------\n");
        } catch (IOException e) {
            System.err.println("Unable to print build info xml file: \n" +
                    Throwables.getStackTraceAsString(e));
        }
    }
}
