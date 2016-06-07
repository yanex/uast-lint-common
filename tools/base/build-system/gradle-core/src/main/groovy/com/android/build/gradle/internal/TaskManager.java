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

package com.android.build.gradle.internal;

import static com.android.build.OutputFile.DENSITY;
import static com.android.builder.core.BuilderConstants.CONNECTED;
import static com.android.builder.core.BuilderConstants.DEVICE;
import static com.android.builder.core.BuilderConstants.FD_ANDROID_RESULTS;
import static com.android.builder.core.BuilderConstants.FD_ANDROID_TESTS;
import static com.android.builder.core.BuilderConstants.FD_FLAVORS_ALL;
import static com.android.builder.core.VariantType.ANDROID_TEST;
import static com.android.sdklib.BuildToolInfo.PathId.ZIP_ALIGN;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verifyNotNull;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.DefaultContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.Transform;
import com.android.build.gradle.AndroidConfig;
import com.android.build.gradle.AndroidGradleOptions;
import com.android.build.gradle.OptionalCompilationStep;
import com.android.build.gradle.internal.core.Abi;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.coverage.JacocoPlugin;
import com.android.build.gradle.internal.coverage.JacocoReportTask;
import com.android.build.gradle.internal.dependency.LibraryDependencyImpl;
import com.android.build.gradle.internal.dependency.ManifestDependencyImpl;
import com.android.build.gradle.internal.dependency.VariantDependencies;
import com.android.build.gradle.internal.dsl.AbiSplitOptions;
import com.android.build.gradle.internal.dsl.CoreNdkOptions;
import com.android.build.gradle.internal.dsl.DexOptions;
import com.android.build.gradle.internal.dsl.PackagingOptions;
import com.android.build.gradle.internal.incremental.BuildInfoLoaderTask;
import com.android.build.gradle.internal.incremental.InstantRunAnchorTask;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext;
import com.android.build.gradle.internal.incremental.InstantRunPatchingPolicy;
import com.android.build.gradle.internal.incremental.InstantRunVerifierStatus;
import com.android.build.gradle.internal.incremental.InstantRunWrapperTask;
import com.android.build.gradle.internal.pipeline.ExtendedContentType;
import com.android.build.gradle.internal.pipeline.OriginalStream;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.pipeline.TransformStream;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.publishing.ApkPublishArtifact;
import com.android.build.gradle.internal.publishing.MappingPublishArtifact;
import com.android.build.gradle.internal.publishing.MetadataPublishArtifact;
import com.android.build.gradle.internal.scope.AndroidTask;
import com.android.build.gradle.internal.scope.AndroidTaskRegistry;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantOutputScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.AndroidReportTask;
import com.android.build.gradle.internal.tasks.CheckManifest;
import com.android.build.gradle.internal.tasks.DependencyReportTask;
import com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask;
import com.android.build.gradle.internal.tasks.FileSupplier;
import com.android.build.gradle.internal.tasks.GenerateApkDataTask;
import com.android.build.gradle.internal.tasks.InstallVariantTask;
import com.android.build.gradle.internal.tasks.MockableAndroidJarTask;
import com.android.build.gradle.internal.tasks.PrepareDependenciesTask;
import com.android.build.gradle.internal.tasks.SigningReportTask;
import com.android.build.gradle.internal.tasks.SourceSetsTask;
import com.android.build.gradle.internal.tasks.TestServerTask;
import com.android.build.gradle.internal.tasks.UninstallTask;
import com.android.build.gradle.internal.tasks.databinding.DataBindingExportBuildInfoTask;
import com.android.build.gradle.internal.tasks.databinding.DataBindingProcessLayoutsTask;
import com.android.build.gradle.internal.tasks.multidex.CreateManifestKeepList;
import com.android.build.gradle.internal.test.TestDataImpl;
import com.android.build.gradle.internal.test.report.ReportType;
import com.android.build.gradle.internal.transforms.DexTransform;
import com.android.build.gradle.internal.transforms.ExtractJarsTransform;
import com.android.build.gradle.internal.transforms.InstantRunBuildType;
import com.android.build.gradle.internal.transforms.InstantRunDex;
import com.android.build.gradle.internal.transforms.InstantRunSlicer;
import com.android.build.gradle.internal.transforms.InstantRunTransform;
import com.android.build.gradle.internal.transforms.InstantRunVerifierTransform;
import com.android.build.gradle.internal.transforms.JacocoTransform;
import com.android.build.gradle.internal.transforms.JarMergingTransform;
import com.android.build.gradle.internal.transforms.NoChangesVerifierTransform;
import com.android.build.gradle.internal.transforms.MergeJavaResourcesTransform;
import com.android.build.gradle.internal.transforms.MultiDexTransform;
import com.android.build.gradle.internal.transforms.NewShrinkerTransform;
import com.android.build.gradle.internal.transforms.ProGuardTransform;
import com.android.build.gradle.internal.transforms.ProguardConfigurable;
import com.android.build.gradle.internal.transforms.ShrinkResourcesTransform;
import com.android.build.gradle.internal.variant.ApkVariantData;
import com.android.build.gradle.internal.variant.ApkVariantOutputData;
import com.android.build.gradle.internal.variant.ApplicationVariantData;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.BaseVariantOutputData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.internal.variant.TestVariantData;
import com.android.build.gradle.tasks.AidlCompile;
import com.android.build.gradle.tasks.AndroidJarTask;
import com.android.build.gradle.tasks.ColdswapArtifactsKickerTask;
import com.android.build.gradle.tasks.CompatibleScreensManifest;
import com.android.build.gradle.tasks.GenerateBuildConfig;
import com.android.build.gradle.tasks.GenerateResValues;
import com.android.build.gradle.tasks.GenerateSplitAbiRes;
import com.android.build.gradle.tasks.JackTask;
import com.android.build.gradle.tasks.JillTask;
import com.android.build.gradle.tasks.Lint;
import com.android.build.gradle.tasks.MergeManifests;
import com.android.build.gradle.tasks.MergeResources;
import com.android.build.gradle.tasks.MergeSourceSetFolders;
import com.android.build.gradle.tasks.NdkCompile;
import com.android.build.gradle.tasks.PackageApplication;
import com.android.build.gradle.tasks.PackageSplitAbi;
import com.android.build.gradle.tasks.PackageSplitRes;
import com.android.build.gradle.tasks.PrePackageApplication;
import com.android.build.gradle.tasks.ProcessAndroidResources;
import com.android.build.gradle.tasks.ProcessManifest;
import com.android.build.gradle.tasks.ProcessTestManifest;
import com.android.build.gradle.tasks.RenderscriptCompile;
import com.android.build.gradle.tasks.SplitZipAlign;
import com.android.build.gradle.tasks.ZipAlign;
import com.android.build.gradle.tasks.factory.JavaCompileConfigAction;
import com.android.build.gradle.tasks.factory.ProcessJavaResConfigAction;
import com.android.build.gradle.tasks.factory.UnitTestConfigAction;
import com.android.build.gradle.tasks.fd.FastDeployRuntimeExtractorTask;
import com.android.build.gradle.tasks.fd.GenerateInstantRunAppInfoTask;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.core.VariantConfiguration;
import com.android.builder.core.VariantType;
import com.android.builder.dependency.LibraryDependency;
import com.android.builder.internal.testing.SimpleTestCallable;
import com.android.builder.model.AndroidProject;
import com.android.builder.model.DataBindingOptions;
import com.android.builder.model.SyncIssue;
import com.android.builder.sdk.SdkLoader;
import com.android.builder.sdk.TargetInfo;
import com.android.builder.testing.ConnectedDeviceProvider;
import com.android.builder.testing.api.DeviceProvider;
import com.android.builder.testing.api.TestServer;
import com.android.manifmerger.ManifestMerger2;
import com.android.sdklib.AndroidVersion;
import com.android.utils.StringHelper;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import android.databinding.tool.DataBindingBuilder;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import groovy.lang.Closure;

/**
 * Manages tasks creation.
 */
public abstract class TaskManager {

    public static final String FILE_JACOCO_AGENT = "jacocoagent.jar";

    public static final String DEFAULT_PROGUARD_CONFIG_FILE = "proguard-android.txt";

    public static final String DIR_BUNDLES = "bundles";

    public static final String INSTALL_GROUP = "Install";

    public static final String BUILD_GROUP = BasePlugin.BUILD_GROUP;

    public static final String ANDROID_GROUP = "Android";

    protected Project project;

    protected AndroidBuilder androidBuilder;

    protected DataBindingBuilder dataBindingBuilder;

    private DependencyManager dependencyManager;

    protected SdkHandler sdkHandler;

    protected AndroidConfig extension;

    protected ToolingModelBuilderRegistry toolingRegistry;

    private final GlobalScope globalScope;

    private final AndroidTaskRegistry androidTasks = new AndroidTaskRegistry();

    private Logger logger;

    protected boolean isComponentModelPlugin = false;

    // Task names
    // TODO: Convert to AndroidTask.
    private static final String MAIN_PREBUILD = "preBuild";

    private static final String UNINSTALL_ALL = "uninstallAll";

    private static final String DEVICE_CHECK = "deviceCheck";

    protected static final String CONNECTED_CHECK = "connectedCheck";

    private static final String ASSEMBLE_ANDROID_TEST = "assembleAndroidTest";

    private static final String SOURCE_SETS = "sourceSets";

    private static final String LINT = "lint";

    protected static final String LINT_COMPILE = "compileLint";

    // Tasks
    private Copy jacocoAgentTask;

    public AndroidTask<MockableAndroidJarTask> createMockableJar;

    public TaskManager(
            Project project,
            AndroidBuilder androidBuilder,
            DataBindingBuilder dataBindingBuilder,
            AndroidConfig extension,
            SdkHandler sdkHandler,
            DependencyManager dependencyManager,
            ToolingModelBuilderRegistry toolingRegistry) {
        this.project = project;
        this.androidBuilder = androidBuilder;
        this.dataBindingBuilder = dataBindingBuilder;
        this.sdkHandler = sdkHandler;
        this.extension = extension;
        this.toolingRegistry = toolingRegistry;
        this.dependencyManager = dependencyManager;
        logger = Logging.getLogger(this.getClass());

        globalScope = new GlobalScope(
                project,
                androidBuilder,
                extension,
                sdkHandler,
                toolingRegistry);
    }

    private boolean isVerbose() {
        return project.getLogger().isEnabled(LogLevel.INFO);
    }

    private boolean isDebugLog() {
        return project.getLogger().isEnabled(LogLevel.DEBUG);
    }

    public DataBindingBuilder getDataBindingBuilder() {
        return dataBindingBuilder;
    }

    /**
     * Creates the tasks for a given BaseVariantData.
     */
    public abstract void createTasksForVariantData(
            @NonNull TaskFactory tasks,
            @NonNull BaseVariantData<? extends BaseVariantOutputData> variantData);

    public GlobalScope getGlobalScope() {
        return globalScope;
    }

    /**
     * Returns a collection of buildables that creates native object.
     *
     * A buildable is considered to be any object that can be used as the argument to
     * Task.dependsOn.  This could be a Task or a BuildableModelElement (e.g. BinarySpec).
     */
    protected Collection<Object> getNdkBuildable(BaseVariantData variantData) {
        return Collections.<Object>singleton(variantData.ndkCompileTask);
    }

    /**
     * Override to configure NDK data in the scope.
     */
    public void configureScopeForNdk(@NonNull VariantScope scope) {
        final BaseVariantData variantData = scope.getVariantData();
        scope.setNdkSoFolder(Collections.singleton(new File(
                scope.getGlobalScope().getIntermediatesDir(),
                "ndk/" + variantData.getVariantConfiguration().getDirName() + "/lib")));
        File objFolder = new File(scope.getGlobalScope().getIntermediatesDir(),
                "ndk/" + variantData.getVariantConfiguration().getDirName() + "/obj");
        scope.setNdkObjFolder(objFolder);
        for (Abi abi : NdkHandler.getAbiList()) {
            scope.addNdkDebuggableLibraryFolders(abi,
                    new File(objFolder, "local/" + abi.getName()));
        }

    }

    protected AndroidConfig getExtension() {
        return extension;
    }

    public void resolveDependencies(
            @NonNull VariantDependencies variantDeps,
            @Nullable VariantDependencies testedVariantDeps,
            @Nullable String testedProjectPath) {
        dependencyManager.resolveDependencies(variantDeps, testedVariantDeps, testedProjectPath);
    }

    /**
     * Create tasks before the evaluation (on plugin apply). This is useful for tasks that
     * could be referenced by custom build logic.
     */
    public void createTasksBeforeEvaluate(@NonNull TaskFactory tasks) {
        tasks.create(UNINSTALL_ALL, new Action<Task>() {
            @Override
            public void execute(Task uninstallAllTask) {
                uninstallAllTask.setDescription("Uninstall all applications.");
                uninstallAllTask.setGroup(INSTALL_GROUP);
            }
        });

        tasks.create(DEVICE_CHECK, new Action<Task>() {
            @Override
            public void execute(Task deviceCheckTask) {
                deviceCheckTask.setDescription(
                        "Runs all device checks using Device Providers and Test Servers.");
                deviceCheckTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
            }
        });

        tasks.create(CONNECTED_CHECK, new Action<Task>() {
            @Override
            public void execute(Task connectedCheckTask) {
                connectedCheckTask.setDescription(
                        "Runs all device checks on currently connected devices.");
                connectedCheckTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
            }
        });

        tasks.create(MAIN_PREBUILD);

        tasks.create(SOURCE_SETS, SourceSetsTask.class, new Action<SourceSetsTask>() {
            @Override
            public void execute(SourceSetsTask sourceSetsTask) {
                sourceSetsTask.setConfig(extension);
                sourceSetsTask.setDescription(
                        "Prints out all the source sets defined in this project.");
                sourceSetsTask.setGroup(ANDROID_GROUP);
            }
        });

        tasks.create(ASSEMBLE_ANDROID_TEST, new Action<Task>() {
            @Override
            public void execute(Task assembleAndroidTestTask) {
                assembleAndroidTestTask.setGroup(BasePlugin.BUILD_GROUP);
                assembleAndroidTestTask.setDescription("Assembles all the Test applications.");
            }
        });

        tasks.create(LINT, Lint.class, new Action<Lint>() {
            @Override
            public void execute(Lint lintTask) {
                lintTask.setDescription("Runs lint on all variants.");
                lintTask.setVariantName("");
                lintTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                lintTask.setLintOptions(getExtension().getLintOptions());
                lintTask.setSdkHome(sdkHandler.getSdkFolder());
                lintTask.setToolingRegistry(toolingRegistry);
                lintTask.setAndroidBuilder(androidBuilder);
            }
        });
        tasks.named(JavaBasePlugin.CHECK_TASK_NAME, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(LINT);
            }
        });
        createLintCompileTask(tasks);
    }

    public void createMockableJarTask(TaskFactory tasks) {
        createMockableJar = androidTasks.create(tasks, new MockableAndroidJarTask.ConfigAction(globalScope));
    }

    protected void createDependencyStreams(@NonNull final VariantScope variantScope) {
        BaseVariantData<? extends BaseVariantOutputData> variantData = variantScope.getVariantData();
        final GradleVariantConfiguration config = variantData.getVariantConfiguration();

        TransformManager transformManager = variantScope.getTransformManager();

        // content that can be found in a jar:
        Set<ContentType> fullJar = ImmutableSet.<ContentType>of(
                DefaultContentType.CLASSES, DefaultContentType.RESOURCES, ExtendedContentType.NATIVE_LIBS);

        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(fullJar)
                .addScope(Scope.PROJECT_LOCAL_DEPS)
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getLocalPackagedJars();
                    }
                })
                .build());

        IncrementalMode incrementalMode = getIncrementalMode(config);
        boolean skipDependency = incrementalMode == IncrementalMode.LOCAL_JAVA_ONLY ||
                incrementalMode == IncrementalMode.LOCAL_RES_ONLY;
        ImmutableList<Object> dependencies = skipDependency ?
                ImmutableList.of() :
                ImmutableList.of(variantData.prepareDependenciesTask,
                        variantData.getVariantDependency().getPackageConfiguration()
                                .getBuildDependencies());

        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_JARS)
                .addScope(Scope.EXTERNAL_LIBRARIES)
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        Set<File> files = config.getExternalPackagedJars();
                        Set<File> additions = variantScope.getGlobalScope().getAndroidBuilder()
                                .getAdditionalPackagedJars(config);

                        if (!additions.isEmpty()) {
                            ImmutableSet.Builder<File> builder = ImmutableSet.builder();
                            return builder.addAll(files).addAll(additions).build();
                        }

                        return files;
                    }
                })
                .setDependencies(dependencies)
                .build());

        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_NATIVE_LIBS)
                .addScope(Scope.EXTERNAL_LIBRARIES)
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        Set<File> files = config.getExternalPackagedJniJars();
                        Set<File> additions = variantScope.getGlobalScope().getAndroidBuilder()
                                .getAdditionalPackagedJars(config);

                        if (!additions.isEmpty()) {
                            ImmutableSet.Builder<File> builder = ImmutableSet.builder();
                            return builder.addAll(files).addAll(additions).build();
                        }

                        return files;
                    }
                })
                .setFolders(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getExternalAarJniLibFolders();
                    }
                })
                .setDependencies(dependencies)
                .build());

        // for the sub modules, only the main jar has resources.
        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_JARS)
                .addScope(Scope.SUB_PROJECTS)
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getSubProjectPackagedJars();
                    }

                })
                .setDependencies(dependencies)
                .build());

        // the local deps don't have resources (been merged into the main jar)
        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_CLASS)
                .addScope(Scope.SUB_PROJECTS_LOCAL_DEPS)
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getSubProjectLocalPackagedJars();
                    }

                })
                .setDependencies(dependencies)
                .build());

        // and the native libs of the libraries are in a separate folder.
        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_NATIVE_LIBS)
                .addScope(Scope.SUB_PROJECTS)
                .setFolders(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getSubProjectJniLibFolders();
                    }
                })
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getSubProjectPackagedJniJars();
                    }
                })
                .setDependencies(dependencies)
                .build());

        // provided only scopes.
        transformManager.addStream(OriginalStream.builder()
                .addContentTypes(fullJar)
                .addScope(Scope.PROVIDED_ONLY)
                .setJars(new Supplier<Collection<File>>() {
                    @Override
                    public Collection<File> get() {
                        return config.getProvidedOnlyJars();
                    }

                })
                .build());

        if (variantScope.getTestedVariantData() != null) {
            final BaseVariantData testedVariantData = variantScope.getTestedVariantData();

            VariantScope testedVariantScope = testedVariantData.getScope();

            // create two streams of different types.
            transformManager.addStream(OriginalStream.builder()
                    .addContentTypes(DefaultContentType.CLASSES)
                    .addScope(Scope.TESTED_CODE)
                    .setFolders(
                            Suppliers.ofInstance(
                                    (Collection<File>) ImmutableList.of(
                                            testedVariantScope.getJavaOutputDir())))
                    .setDependency(testedVariantScope.getJavacTask().getName())
                    .build());

            transformManager.addStream(OriginalStream.builder()
                    .addContentTypes(DefaultContentType.CLASSES)
                    .addScope(Scope.TESTED_CODE)
                    .setJars(new Supplier<Collection<File>>() {
                                 @Override
                                 public Collection<File> get() {
                                     return variantScope.getGlobalScope().getAndroidBuilder()
                                             .getAllPackagedJars(
                                                     testedVariantData.getVariantConfiguration());
                                 }
                             })
                    .setDependency(ImmutableList.of(
                            testedVariantData.prepareDependenciesTask,
                            testedVariantData.getVariantDependency().getPackageConfiguration()
                                    .getBuildDependencies()))
                    .build());
        }

        handleJacocoDependencies(variantScope);
    }

    public void createMergeAppManifestsTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope variantScope) {

        ApplicationVariantData appVariantData =
                (ApplicationVariantData) variantScope.getVariantData();
        Set<String> screenSizes = appVariantData.getCompatibleScreens();

        // loop on all outputs. The only difference will be the name of the task, and location
        // of the generated manifest
        for (final BaseVariantOutputData vod : appVariantData.getOutputs()) {
            VariantOutputScope scope = vod.getScope();

            AndroidTask<CompatibleScreensManifest> csmTask = null;
            if (vod.getMainOutputFile().getFilter(DENSITY) != null) {
                csmTask = androidTasks.create(tasks,
                        new CompatibleScreensManifest.ConfigAction(scope, screenSizes));
                scope.setCompatibleScreensManifestTask(csmTask);
            }

            List<ManifestMerger2.Invoker.Feature> optionalFeatures = getIncrementalMode(
                    variantScope.getVariantConfiguration()) != IncrementalMode.NONE
                    ? ImmutableList.of(ManifestMerger2.Invoker.Feature.INSTANT_RUN_REPLACEMENT)
                    : ImmutableList.<ManifestMerger2.Invoker.Feature>of();

            scope.setManifestProcessorTask(androidTasks.create(tasks,
                    new MergeManifests.ConfigAction(scope, optionalFeatures)));

            if (csmTask != null) {
                scope.getManifestProcessorTask().dependsOn(tasks, csmTask);
            }
        }
    }

    public void createMergeLibManifestsTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {

        AndroidTask<ProcessManifest> processManifest = androidTasks.create(tasks,
                new ProcessManifest.ConfigAction(scope));

        processManifest.dependsOn(tasks, scope.getVariantData().prepareDependenciesTask);

        BaseVariantOutputData variantOutputData = scope.getVariantData().getOutputs().get(0);
        variantOutputData.getScope().setManifestProcessorTask(processManifest);
    }

    protected void createProcessTestManifestTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {

        AndroidTask<ProcessTestManifest> processTestManifestTask = androidTasks.create(tasks,
                new ProcessTestManifest.ConfigAction(scope));

        processTestManifestTask.dependsOn(tasks, scope.getVariantData().prepareDependenciesTask);

        BaseVariantOutputData variantOutputData = scope.getVariantData().getOutputs().get(0);
        variantOutputData.getScope().setManifestProcessorTask(processTestManifestTask);
    }

    public void createRenderscriptTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {
        scope.setRenderscriptCompileTask(
                androidTasks.create(tasks, new RenderscriptCompile.ConfigAction(scope)));

        BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        GradleVariantConfiguration config = variantData.getVariantConfiguration();

        if (config.getRenderscriptSupportModeEnabled() && config.getRenderscriptTarget() >= 21) {
            androidBuilder.getErrorReporter().handleSyncError(
                    "",
                    SyncIssue.TYPE_GENERIC,
                    "Renderscript support mode is not currently supported with renderscript target 21+");
        }

        // get single output for now.
        BaseVariantOutputData variantOutputData = variantData.getOutputs().get(0);

        scope.getRenderscriptCompileTask().dependsOn(tasks, variantData.prepareDependenciesTask);
        if (config.getType().isForTesting()) {
            scope.getRenderscriptCompileTask().dependsOn(tasks,
                    variantOutputData.getScope().getManifestProcessorTask());
        } else {
            scope.getRenderscriptCompileTask().dependsOn(tasks, scope.getCheckManifestTask());
        }

        scope.getResourceGenTask().dependsOn(tasks, scope.getRenderscriptCompileTask());
        // only put this dependency if rs will generate Java code
        if (!config.getRenderscriptNdkModeEnabled()) {
            scope.getSourceGenTask().dependsOn(tasks, scope.getRenderscriptCompileTask());
        }

    }

    public AndroidTask<MergeResources> createMergeResourcesTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {
        return basicCreateMergeResourcesTask(
                tasks,
                scope,
                "merge",
                null /*outputLocation*/,
                true /*includeDependencies*/,
                true /*process9patch*/);
    }

    public AndroidTask<MergeResources> basicCreateMergeResourcesTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope,
            @NonNull String taskNamePrefix,
            @Nullable File outputLocation,
            final boolean includeDependencies,
            final boolean process9Patch) {
        AndroidTask<MergeResources> mergeResourcesTask = androidTasks.create(tasks,
                new MergeResources.ConfigAction(
                        scope,
                        taskNamePrefix,
                        outputLocation,
                        includeDependencies,
                        process9Patch));

        if (getIncrementalMode(scope.getVariantConfiguration()) != IncrementalMode.LOCAL_RES_ONLY) {
            mergeResourcesTask.dependsOn(tasks,
                    scope.getVariantData().prepareDependenciesTask,
                    scope.getResourceGenTask());
        }
        scope.setMergeResourcesTask(mergeResourcesTask);
        scope.setResourceOutputDir(
                Objects.firstNonNull(outputLocation, scope.getDefaultMergeResourcesOutputDir()));
        scope.setMergeResourceOutputDir(outputLocation);
        return scope.getMergeResourcesTask();
    }

    public void createMergeAssetsTask(TaskFactory tasks, VariantScope scope) {
        AndroidTask<MergeSourceSetFolders> mergeAssetsTask = androidTasks.create(tasks,
                new MergeSourceSetFolders.MergeAssetConfigAction(scope));
        mergeAssetsTask.dependsOn(tasks,
                scope.getVariantData().prepareDependenciesTask,
                scope.getAssetGenTask());
        scope.setMergeAssetsTask(mergeAssetsTask);
    }

    public void createMergeJniLibFoldersTasks(
            @NonNull TaskFactory tasks,
            @NonNull final VariantScope variantScope) {
        // merge the source folders together using the proper priority.
        AndroidTask<MergeSourceSetFolders> mergeJniLibFoldersTask = androidTasks.create(tasks,
                new MergeSourceSetFolders.MergeJniLibFoldersConfigAction(variantScope));
        mergeJniLibFoldersTask.dependsOn(tasks,
                variantScope.getVariantData().prepareDependenciesTask,
                variantScope.getAssetGenTask());
        variantScope.setMergeJniLibFoldersTask(mergeJniLibFoldersTask);

        // create the stream generated from this task
        variantScope.getTransformManager().addStream(OriginalStream.builder()
                .addContentType(ExtendedContentType.NATIVE_LIBS)
                .addScope(Scope.PROJECT)
                .setFolder(variantScope.getMergeNativeLibsOutputDir())
                .setDependency(mergeJniLibFoldersTask.getName())
                .build());


        // create a stream that contains the content of the local NDK build
        variantScope.getTransformManager().addStream(OriginalStream.builder()
                .addContentType(ExtendedContentType.NATIVE_LIBS)
                .addScope(Scope.PROJECT)
                .setFolders(Suppliers.ofInstance(variantScope.getNdkSoFolder()))
                .setDependency(getNdkBuildable(variantScope.getVariantData()))
                .build());

        // create a stream containing the content of the renderscript compilation output
        // if support mode is enabled.
        if (variantScope.getVariantConfiguration().getRenderscriptSupportModeEnabled()) {
            variantScope.getTransformManager().addStream(OriginalStream.builder()
                    .addContentType(ExtendedContentType.NATIVE_LIBS)
                    .addScope(Scope.PROJECT)
                    .setFolders(new Supplier<Collection<File>>() {
                        @Override
                        public Collection<File> get() {
                            ImmutableList.Builder<File> builder = ImmutableList.builder();

                            if (variantScope.getRenderscriptLibOutputDir().isDirectory()) {
                                builder.add(variantScope.getRenderscriptLibOutputDir());
                            }

                            File rsLibs = variantScope.getGlobalScope().getAndroidBuilder()
                                    .getSupportNativeLibFolder();
                            if (rsLibs != null && rsLibs.isDirectory()) {
                                builder.add(rsLibs);
                            }

                            return builder.build();
                        }
                    })
                    .setDependency(variantScope.getRenderscriptCompileTask().getName())
                .build());
        }

        // compute the scopes that need to be merged.
        Set<Scope> mergeScopes = getResMergingScopes(variantScope);
        // Create the merge transform
        MergeJavaResourcesTransform mergeTransform = new MergeJavaResourcesTransform(
                variantScope.getGlobalScope().getExtension().getPackagingOptions(),
                mergeScopes, ExtendedContentType.NATIVE_LIBS, "mergeJniLibs");
        variantScope.getTransformManager().addTransform(tasks, variantScope, mergeTransform);
    }

    public void createBuildConfigTask(@NonNull TaskFactory tasks, @NonNull VariantScope scope) {
        AndroidTask<GenerateBuildConfig> generateBuildConfigTask =
                androidTasks.create(tasks, new GenerateBuildConfig.ConfigAction(scope));
        scope.setGenerateBuildConfigTask(generateBuildConfigTask);
        scope.getSourceGenTask().dependsOn(tasks, generateBuildConfigTask.getName());
        if (scope.getVariantConfiguration().getType().isForTesting()) {
            // in case of a test project, the manifest is generated so we need to depend
            // on its creation.

            // For test apps there should be a single output, so we get it.
            BaseVariantOutputData variantOutputData = scope.getVariantData().getOutputs().get(0);

            generateBuildConfigTask.dependsOn(
                    tasks, variantOutputData.getScope().getManifestProcessorTask());
        } else {
            generateBuildConfigTask.dependsOn(tasks, scope.getCheckManifestTask());
        }
    }

    public void createGenerateResValuesTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {
        AndroidTask<GenerateResValues> generateResValuesTask = androidTasks.create(
                tasks, new GenerateResValues.ConfigAction(scope));
        scope.getResourceGenTask().dependsOn(tasks, generateResValuesTask);
    }

    public void createApkProcessResTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {
        createProcessResTask(
                tasks,
                scope,
                new File(globalScope.getIntermediatesDir(),
                        "symbols/" + scope.getVariantData().getVariantConfiguration().getDirName()),
                true);
    }

    public void createProcessResTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope,
            @Nullable File symbolLocation,
            boolean generateResourcePackage) {
        BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();

        variantData.calculateFilters(scope.getGlobalScope().getExtension().getSplits());

        // loop on all outputs. The only difference will be the name of the task, and location
        // of the generated data.
        for (BaseVariantOutputData vod : variantData.getOutputs()) {
            final VariantOutputScope variantOutputScope = vod.getScope();

            variantOutputScope.setProcessResourcesTask(androidTasks.create(tasks,
                    new ProcessAndroidResources.ConfigAction(variantOutputScope, symbolLocation,
                            generateResourcePackage)));

            // always depend on merge res,
            variantOutputScope.getProcessResourcesTask().dependsOn(tasks,
                    scope.getMergeResourcesTask());

            if (getIncrementalMode(scope.getVariantConfiguration()) != IncrementalMode.LOCAL_RES_ONLY) {
                variantOutputScope.getProcessResourcesTask().dependsOn(tasks,
                        variantOutputScope.getManifestProcessorTask(),
                        scope.getMergeAssetsTask());
            }

            if (vod.getMainOutputFile().getFilter(DENSITY) == null) {
                scope.setGenerateRClassTask(variantOutputScope.getProcessResourcesTask());
                scope.getSourceGenTask().optionalDependsOn(
                        tasks,
                        variantOutputScope.getProcessResourcesTask());
            }

        }
    }

    /**
     * Creates the split resources packages task if necessary. AAPT will produce split packages for
     * all --split provided parameters. These split packages should be signed and moved unchanged to
     * the APK build output directory.
     */
    @NonNull
    public AndroidTask<PackageSplitRes> createSplitResourcesTasks(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {
        BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();

        checkState(variantData.getSplitHandlingPolicy().equals(
                        BaseVariantData.SplitHandlingPolicy.RELEASE_21_AND_AFTER_POLICY),
                "Can only create split resources tasks for pure splits.");

        List<? extends BaseVariantOutputData> outputs = variantData.getOutputs();
        final BaseVariantOutputData variantOutputData = outputs.get(0);
        if (outputs.size() != 1) {
            throw new RuntimeException(
                    "In release 21 and later, there can be only one main APK, " +
                            "found " + outputs.size());
        }

        VariantOutputScope variantOutputScope = variantOutputData.getScope();
        AndroidTask<PackageSplitRes> packageSplitRes =
                androidTasks.create(tasks, new PackageSplitRes.ConfigAction(scope));
        packageSplitRes.dependsOn(tasks,
                variantOutputScope.getProcessResourcesTask().getName());
        return packageSplitRes;
    }

    @Nullable
    public AndroidTask<PackageSplitAbi> createSplitAbiTasks(
            @NonNull TaskFactory tasks,
            @NonNull final VariantScope scope) {
        ApplicationVariantData variantData = (ApplicationVariantData) scope.getVariantData();

        checkState(variantData.getSplitHandlingPolicy().equals(
                BaseVariantData.SplitHandlingPolicy.RELEASE_21_AND_AFTER_POLICY),
                "split ABI tasks are only compatible with pure splits.");

        Set<String> filters = AbiSplitOptions.getAbiFilters(
                getExtension().getSplits().getAbiFilters());
        if (filters.isEmpty()) {
            return null;
        }

        List<ApkVariantOutputData> outputs = variantData.getOutputs();
        if (outputs.size() != 1) {
            throw new RuntimeException(
                    "In release 21 and later, there can be only one main APK, " +
                            "found " + outputs.size());
        }

        BaseVariantOutputData variantOutputData = outputs.get(0);

        // first create the split APK resources.
        AndroidTask<GenerateSplitAbiRes> generateSplitAbiRes =
                androidTasks.create(tasks, new GenerateSplitAbiRes.ConfigAction(scope));
        generateSplitAbiRes.dependsOn(tasks,
                variantOutputData.getScope().getProcessResourcesTask().getName());

        // then package those resources with the appropriate JNI libraries.
        AndroidTask<PackageSplitAbi> packageSplitAbiTask =
                androidTasks.create(tasks, new PackageSplitAbi.ConfigAction(scope));

        packageSplitAbiTask.dependsOn(tasks, generateSplitAbiRes);
        packageSplitAbiTask.dependsOn(tasks, scope.getNdkBuildable());

        // set up dependency on the jni merger.
        for (TransformStream stream : scope.getTransformManager().getStreams(
                PackageApplication.sNativeLibsFilter)) {
            packageSplitAbiTask.dependsOn(tasks, stream.getDependencies());
        }
        return packageSplitAbiTask;
    }

    public void createSplitTasks(@NonNull TaskFactory tasks, @NonNull final VariantScope scope) {
        AndroidTask<PackageSplitRes> packageSplitResourcesTask =
                createSplitResourcesTasks(tasks, scope);
        final AndroidTask<PackageSplitAbi> packageSplitAbiTask = createSplitAbiTasks(tasks, scope);

        AndroidTask<SplitZipAlign> zipAlign =
                androidTasks.create(tasks, new SplitZipAlign.ConfigAction(scope));
        //noinspection VariableNotUsedInsideIf - only need to check if task exist.
        if (packageSplitAbiTask != null) {
            zipAlign.configure(tasks, new Action<Task>() {
                @Override
                public void execute(Task task) {
                    ((SplitZipAlign)task).getAbiInputFiles().addAll(
                            scope.getPackageSplitAbiOutputFiles());
                }
            });
        }
        zipAlign.dependsOn(tasks, packageSplitResourcesTask);
        zipAlign.optionalDependsOn(tasks, packageSplitAbiTask);

        BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        VariantOutputScope outputScope = variantData.getOutputs().get(0).getScope();
        outputScope.setSplitZipAlignTask(zipAlign);
    }

    /**
     * Returns the scopes for which the java resources should be merged.
     * @param variantScope the scope of the variant being processed.
     * @return the list of scopes for which to merge the java resources.
     */
    @NonNull
    protected abstract Set<Scope> getResMergingScopes(@NonNull VariantScope variantScope);

    /**
     * Creates the java resources processing tasks.
     *
     * The java processing will happen in two steps:
     * <ul>
     * <li>{@link Sync} task configured with {@link ProcessJavaResConfigAction} will sync all source
     * folders into a single folder identified by
     * {@link VariantScope#getSourceFoldersJavaResDestinationDir()}</li>
     * <li>{@link MergeJavaResourcesTransform} will take the output of this merge plus the
     * dependencies and will create a single merge with the {@link PackagingOptions} settings
     * applied.</li>
     * </ul>
     *
     * @param tasks tasks factory to create tasks.
     * @param variantScope the variant scope we are operating under.
     */
    public void createProcessJavaResTasks(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope variantScope) {
        TransformManager transformManager = variantScope.getTransformManager();

        // now copy the source folders java resources into the temporary location, mainly to
        // maintain the PluginDsl COPY semantics.
        AndroidTask<Sync> processJavaResourcesTask =
                androidTasks.create(tasks, new ProcessJavaResConfigAction(variantScope));
        variantScope.setProcessJavaResourcesTask(processJavaResourcesTask);

        // create the stream generated from this task
        variantScope.getTransformManager().addStream(OriginalStream.builder()
                .addContentType(DefaultContentType.RESOURCES)
                .addScope(Scope.PROJECT)
                .setFolder(variantScope.getSourceFoldersJavaResDestinationDir())
                .setDependency(processJavaResourcesTask.getName())
                .build());

        // compute the scopes that need to be merged.
        Set<Scope> mergeScopes = getResMergingScopes(variantScope);

        // Create the merge transform
        MergeJavaResourcesTransform mergeTransform = new MergeJavaResourcesTransform(
                variantScope.getGlobalScope().getExtension().getPackagingOptions(),
                mergeScopes, DefaultContentType.RESOURCES, "mergeJavaRes");

        variantScope.setMergeJavaResourcesTask(
                transformManager.addTransform(tasks, variantScope, mergeTransform));
    }

    public void createAidlTask(@NonNull TaskFactory tasks, @NonNull VariantScope scope) {
        scope.setAidlCompileTask(androidTasks.create(tasks, new AidlCompile.ConfigAction(scope)));
        scope.getSourceGenTask().dependsOn(tasks, scope.getAidlCompileTask());
        scope.getAidlCompileTask().dependsOn(tasks, scope.getVariantData().prepareDependenciesTask);
    }

    /**
     * Creates the task for creating *.class files using javac. These tasks are created regardless
     * of whether Jack is used or not, but assemble will not depend on them if it is. They are
     * always used when running unit tests.
     */
    public AndroidTask<? extends JavaCompile> createJavacTask(
            @NonNull final TaskFactory tasks,
            @NonNull final VariantScope scope) {
        final BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        final AndroidTask<? extends JavaCompile> javacTask = androidTasks.create(tasks,
                new JavaCompileConfigAction(scope));
        scope.setJavacTask(javacTask);

        setupCompileTaskDependencies(tasks, scope, variantData, javacTask);

        // create the output stream from this task
        scope.getTransformManager().addStream(OriginalStream.builder()
                .addContentType(DefaultContentType.CLASSES)
                .addScope(Scope.PROJECT)
                .setFolder(scope.getJavaOutputDir())
                .setDependency(javacTask.getName()).build());

        // Create jar task for uses by external modules.
        if (variantData.getVariantDependency().getClassesConfiguration() != null) {
            tasks.create(scope.getTaskName("package", "JarArtifact"), Jar.class, new Action<Jar>() {
                @Override
                public void execute(Jar jar) {
                    variantData.classesJarTask = jar;
                    jar.dependsOn(javacTask.getName());

                    // add the class files (whether they are instrumented or not.
                    jar.from(scope.getJavaOutputDir());

                    jar.setDestinationDir(new File(
                            scope.getGlobalScope().getIntermediatesDir(),
                            "classes-jar/" +
                                    variantData.getVariantConfiguration().getDirName()));
                    jar.setArchiveName("classes.jar");
                }
            });
        }

        return javacTask;
    }

    private void setupCompileTaskDependencies(@NonNull TaskFactory tasks,
            @NonNull VariantScope scope,
            BaseVariantData<? extends BaseVariantOutputData> variantData,
            AndroidTask<?> compileTask) {
        IncrementalMode incrementalMode = getIncrementalMode(scope.getVariantConfiguration());

        if (incrementalMode == IncrementalMode.LOCAL_RES_ONLY) {
            // in this case only depend on the R class. We want to ignore the other
            // source generating classes like RS, aidl, etc...
            compileTask.optionalDependsOn(tasks, scope.getGenerateRClassTask());
        } else if (incrementalMode != IncrementalMode.LOCAL_JAVA_ONLY) {
            compileTask.optionalDependsOn(tasks, scope.getSourceGenTask());
            compileTask.dependsOn(tasks, scope.getVariantData().prepareDependenciesTask);
            // TODO - dependency information for the compile classpath is being lost.
            // Add a temporary approximation
            compileTask.dependsOn(tasks,
                    scope.getVariantData().getVariantDependency().getCompileConfiguration()
                            .getBuildDependencies());
        }

        if (variantData.getType().isForTesting()) {
            BaseVariantData testedVariantData =
                    (BaseVariantData) ((TestVariantData) variantData).getTestedVariantData();
            final JavaCompile testedJavacTask = testedVariantData.javacTask;
            compileTask.dependsOn(tasks,
                    testedJavacTask != null ? testedJavacTask :
                            testedVariantData.getScope().getJavacTask());
        }
    }

    /**
     * Makes the given task the one used by top-level "compile" task.
     */
    public static void setJavaCompilerTask(
            @NonNull AndroidTask<? extends AbstractCompile> javaCompilerTask,
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {
        scope.getCompileTask().dependsOn(tasks, javaCompilerTask);
        scope.setJavaCompilerTask(javaCompilerTask);

        // TODO: Get rid of it once we stop keeping tasks in variant data.
        //noinspection VariableNotUsedInsideIf
        if (scope.getVariantData().javacTask != null) {
            // This is not the experimental plugin, let's update variant data, so Variants API
            // keeps working.
            scope.getVariantData().javaCompilerTask =  (AbstractCompile) tasks.named(javaCompilerTask.getName());
        }
    }

    public void createGenerateMicroApkDataTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope,
            @NonNull Configuration config) {
        AndroidTask<GenerateApkDataTask> generateMicroApkTask = androidTasks.create(tasks,
                new GenerateApkDataTask.ConfigAction(scope, config));
        generateMicroApkTask.dependsOn(tasks, config);

        // the merge res task will need to run after this one.
        scope.getResourceGenTask().dependsOn(tasks, generateMicroApkTask);
    }

    public void createNdkTasks(@NonNull VariantScope scope) {
        final BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        NdkCompile ndkCompile = project.getTasks().create(
                scope.getTaskName("compile", "Ndk"),
                NdkCompile.class);

        ndkCompile.dependsOn(variantData.preBuildTask);

        ndkCompile.setAndroidBuilder(androidBuilder);
        ndkCompile.setVariantName(variantData.getName());
        ndkCompile.setNdkDirectory(sdkHandler.getNdkFolder());
        ndkCompile.setIsForTesting(variantData.getType().isForTesting());
        variantData.ndkCompileTask = ndkCompile;
        variantData.compileTask.dependsOn(variantData.ndkCompileTask);

        final GradleVariantConfiguration variantConfig = variantData.getVariantConfiguration();

        if (Boolean.TRUE.equals(variantConfig.getMergedFlavor().getRenderscriptNdkModeEnabled())) {
            ndkCompile.setNdkRenderScriptMode(true);
            ndkCompile.dependsOn(variantData.renderscriptCompileTask);
        } else {
            ndkCompile.setNdkRenderScriptMode(false);
        }

        ConventionMappingHelper.map(ndkCompile, "sourceFolders", new Callable<List<File>>() {
            @Override
            public List<File> call() {
                List<File> sourceList = variantConfig.getJniSourceList();
                if (Boolean.TRUE.equals(
                        variantConfig.getMergedFlavor().getRenderscriptNdkModeEnabled())) {
                    sourceList.add(variantData.renderscriptCompileTask.getSourceOutputDir());
                }

                return sourceList;
            }
        });

        ndkCompile.setGeneratedMakefile(new File(scope.getGlobalScope().getIntermediatesDir(),
                "ndk/" + variantData.getVariantConfiguration().getDirName() + "/Android.mk"));

        ConventionMappingHelper.map(ndkCompile, "ndkConfig", new Callable<CoreNdkOptions>() {
            @Override
            public CoreNdkOptions call() {
                return variantConfig.getNdkConfig();
            }
        });

        ConventionMappingHelper.map(ndkCompile, "debuggable", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return variantConfig.getBuildType().isJniDebuggable();
            }
        });

        ndkCompile.setObjFolder(new File(scope.getGlobalScope().getIntermediatesDir(),
                "ndk/" + variantData.getVariantConfiguration().getDirName() + "/obj"));

        Collection<File> ndkSoFolder = scope.getNdkSoFolder();
        if (ndkSoFolder != null && !ndkSoFolder.isEmpty()) {
            ndkCompile.setSoFolder(ndkSoFolder.iterator().next());
        }
    }

    /**
     * Creates the tasks to build unit tests.
     */
    public void createUnitTestVariantTasks(
            @NonNull TaskFactory tasks,
            @NonNull TestVariantData variantData) {
        VariantScope variantScope = variantData.getScope();
        BaseVariantData testedVariantData = variantScope.getTestedVariantData();
        checkState(testedVariantData != null);

        createPreBuildTasks(tasks, variantScope);

        // Create all current streams (dependencies mostly at this point)
        createDependencyStreams(variantScope);

        createProcessJavaResTasks(tasks, variantScope);
        createCompileAnchorTask(tasks, variantScope);

        // :app:compileDebugUnitTestSources should be enough for running tests from AS, so add an
        // explicit dependency on resource copying tasks.
        variantScope.getCompileTask().dependsOn(
                tasks,
                variantScope.getProcessJavaResourcesTask(),
                testedVariantData.getScope().getProcessJavaResourcesTask());

        AndroidTask<? extends JavaCompile> javacTask = createJavacTask(tasks, variantScope);
        setJavaCompilerTask(javacTask, tasks, variantScope);
        createRunUnitTestTask(tasks, variantScope);

        variantData.assembleVariantTask.dependsOn(createMockableJar.getName());
        // This hides the assemble unit test task from the task list.
        variantData.assembleVariantTask.setGroup(null);
    }

    /**
     * Creates the tasks to build android tests.
     */
    public void createAndroidTestVariantTasks(@NonNull TaskFactory tasks,
            @NonNull TestVariantData variantData) {
        VariantScope variantScope = variantData.getScope();

        // get single output for now (though this may always be the case for tests).
        final BaseVariantOutputData variantOutputData = variantData.getOutputs().get(0);

        final BaseVariantData<BaseVariantOutputData> baseTestedVariantData =
                (BaseVariantData<BaseVariantOutputData>) variantData.getTestedVariantData();
        final BaseVariantOutputData testedVariantOutputData =
                baseTestedVariantData.getOutputs().get(0);

        createAnchorTasks(tasks, variantScope);

        // Create all current streams (dependencies mostly at this point)
        createDependencyStreams(variantScope);

        // Add a task to process the manifest
        createProcessTestManifestTask(tasks, variantScope);

        // Add a task to create the res values
        createGenerateResValuesTask(tasks, variantScope);

        // Add a task to compile renderscript files.
        createRenderscriptTask(tasks, variantScope);

        // Add a task to merge the resource folders
        createMergeResourcesTask(tasks, variantScope);

        // Add a task to merge the assets folders
        createMergeAssetsTask(tasks, variantScope);

        if (variantData.getTestedVariantData().getVariantConfiguration().getType().equals(
                VariantType.LIBRARY)) {
            // in this case the tested library must be fully built before test can be built!
            if (testedVariantOutputData.assembleTask != null) {
                variantOutputData.getScope().getManifestProcessorTask().dependsOn(
                        tasks, testedVariantOutputData.assembleTask);
                variantScope.getMergeResourcesTask().dependsOn(
                        tasks, testedVariantOutputData.assembleTask);
            }
        }

        // Add a task to create the BuildConfig class
        createBuildConfigTask(tasks, variantScope);

        // Add a task to generate resource source files
        createApkProcessResTask(tasks, variantScope);

        // process java resources
        createProcessJavaResTasks(tasks, variantScope);

        createAidlTask(tasks, variantScope);

        // Add NDK tasks
        if (!isComponentModelPlugin) {
            createNdkTasks(variantScope);
        }
        variantScope.setNdkBuildable(getNdkBuildable(variantData));

        // add tasks to merge jni libs.
        createMergeJniLibFoldersTasks(tasks, variantScope);

        // Add a task to compile the test application
        if (variantData.getVariantConfiguration().getUseJack()) {
            createJackTask(tasks, variantScope);
        } else {
            AndroidTask<? extends JavaCompile> javacTask = createJavacTask(tasks, variantScope);
            setJavaCompilerTask(javacTask, tasks, variantScope);
            createPostCompilationTasks(tasks, variantScope);
        }

        // Add data binding tasks if enabled
        if (extension.getDataBinding().isEnabled()) {
            createDataBindingTasks(tasks, variantScope);
        }

        createPackagingTask(tasks, variantScope, false /*publishApk*/,
                null /* buildInfoGeneratorTask */);

        tasks.named(ASSEMBLE_ANDROID_TEST, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(variantOutputData.assembleTask);
            }
        });

        createConnectedTestForVariant(tasks, variantScope);
    }


    protected enum IncrementalMode {
        NONE, FULL, LOCAL_JAVA_ONLY, LOCAL_RES_ONLY;
    }

    /**
     * Returns the incremental mode for this variant.
     * @param config the variant's configuration
     * @return the {@link IncrementalMode} for this variant.
     */
    protected IncrementalMode getIncrementalMode(@NonNull GradleVariantConfiguration config) {
        if (config.isInstantRunSupported()
                && targetDeviceSupportsInstantRun(config, project)
                && globalScope.isActive(OptionalCompilationStep.INSTANT_DEV)) {
            if (isComponentModelPlugin) {
                return IncrementalMode.FULL;
            }

            // while both LOCAL_RES and LOCAL_JAVA could be active, LOCAL_RES is higher priority.
            if (globalScope.isActive(OptionalCompilationStep.LOCAL_RES_ONLY)) {
                return IncrementalMode.LOCAL_RES_ONLY;
            }

            if (globalScope.isActive(OptionalCompilationStep.LOCAL_JAVA_ONLY)) {
                return IncrementalMode.LOCAL_JAVA_ONLY;
            }

            return IncrementalMode.FULL;
        }

        return IncrementalMode.NONE;
    }

    private static boolean targetDeviceSupportsInstantRun(
            @NonNull GradleVariantConfiguration config,
            @NonNull Project project) {
        if (config.isLegacyMultiDexMode()) {
            // We don't support legacy multi-dex on Dalvik.
            return AndroidGradleOptions.getTargetApiLevel(project)
                    .compareTo(AndroidVersion.ART_RUNTIME) >= 0;
        }

        return true;
    }

    // TODO - should compile src/lint/java from src/lint/java and jar it into build/lint/lint.jar
    private void createLintCompileTask(TaskFactory tasks) {

        // TODO: move doFirst into dedicated task class.
        tasks.create(LINT_COMPILE, Task.class,
                new Action<Task>() {
                    @Override
                    public void execute(Task lintCompile) {
                        final File outputDir =
                                new File(getGlobalScope().getIntermediatesDir(), "lint");

                        lintCompile.doFirst(new Action<Task>() {
                            @Override
                            public void execute(Task task) {
                                // create the directory for lint output if it does not exist.
                                if (!outputDir.exists()) {
                                    boolean mkdirs = outputDir.mkdirs();
                                    if (!mkdirs) {
                                        throw new GradleException(
                                                "Unable to create lint output directory.");
                                    }
                                }
                            }
                        });
                    }
                });
    }

    /**
     * Is the given variant relevant for lint?
     */
    private static boolean isLintVariant(
            @NonNull BaseVariantData<? extends BaseVariantOutputData> baseVariantData) {
        // Only create lint targets for variants like debug and release, not debugTest
        VariantConfiguration config = baseVariantData.getVariantConfiguration();
        return !config.getType().isForTesting();
    }

    /**
     * Add tasks for running lint on individual variants. We've already added a
     * lint task earlier which runs on all variants.
     */
    public void createLintTasks(TaskFactory tasks, final VariantScope scope) {
        final BaseVariantData<? extends BaseVariantOutputData> baseVariantData =
                scope.getVariantData();
        if (!isLintVariant(baseVariantData)) {
            return;
        }

        // wire the main lint task dependency.
        tasks.named(LINT, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(LINT_COMPILE);
                it.dependsOn(scope.getJavacTask().getName());
            }
        });

        AndroidTask<Lint> variantLintCheck = androidTasks.create(
                tasks, new Lint.ConfigAction(scope));
        variantLintCheck.dependsOn(tasks, LINT_COMPILE, scope.getJavacTask());
    }

    private void createLintVitalTask(@NonNull ApkVariantData variantData) {
        checkState(getExtension().getLintOptions().isCheckReleaseBuilds());
        // TODO: re-enable with Jack when possible
        if (!variantData.getVariantConfiguration().getBuildType().isDebuggable() &&
                !variantData.getVariantConfiguration().getUseJack()) {
            String variantName = variantData.getVariantConfiguration().getFullName();
            String capitalizedVariantName = StringHelper.capitalize(variantName);
            String taskName = "lintVital" + capitalizedVariantName;
            final Lint lintReleaseCheck = project.getTasks().create(taskName, Lint.class);
            lintReleaseCheck.setAndroidBuilder(androidBuilder);
            // TODO: Make this task depend on lintCompile too (resolve initialization order first)
            optionalDependsOn(lintReleaseCheck, variantData.javacTask);
            lintReleaseCheck.setLintOptions(getExtension().getLintOptions());
            lintReleaseCheck.setSdkHome(
                    checkNotNull(sdkHandler.getSdkFolder(), "SDK not set up."));
            lintReleaseCheck.setVariantName(variantName);
            lintReleaseCheck.setToolingRegistry(toolingRegistry);
            lintReleaseCheck.setFatalOnly(true);
            lintReleaseCheck.setDescription(
                    "Runs lint on just the fatal issues in the " + capitalizedVariantName
                            + " build.");

            variantData.assembleVariantTask.dependsOn(lintReleaseCheck);

            // If lint is being run, we do not need to run lint vital.
            // TODO: Find a better way to do this.
            project.getGradle().getTaskGraph().whenReady(new Closure<Void>(this, this) {
                public void doCall(TaskExecutionGraph taskGraph) {
                    if (taskGraph.hasTask(LINT)) {
                        lintReleaseCheck.setEnabled(false);
                    }
                }
            });
        }
    }

    private void createRunUnitTestTask(
            @NonNull TaskFactory tasks,
            @NonNull final VariantScope variantScope) {
        final AndroidTask<Test> runtTestsTask =
                androidTasks.create(tasks, new UnitTestConfigAction(variantScope));
        runtTestsTask.dependsOn(tasks, variantScope.getVariantData().assembleVariantTask);

        tasks.named(JavaPlugin.TEST_TASK_NAME, new Action<Task>() {
            @Override
            public void execute(Task test) {
                test.dependsOn(runtTestsTask.getName());
            }
        });
    }

    public void createTopLevelTestTasks(final TaskFactory tasks, boolean hasFlavors) {
        createMockableJarTask(tasks);

        final List<String> reportTasks = Lists.newArrayListWithExpectedSize(2);

        List<DeviceProvider> providers = getExtension().getDeviceProviders();

        final String connectedRootName = CONNECTED + ANDROID_TEST.getSuffix();
        final String defaultReportsDir = getGlobalScope().getReportsDir().getAbsolutePath()
                + "/" + FD_ANDROID_TESTS;
        final String defaultResultsDir = getGlobalScope().getOutputsDir().getAbsolutePath()
                + "/" + FD_ANDROID_RESULTS;

        // If more than one flavor, create a report aggregator task and make this the parent
        // task for all new connected tasks.  Otherwise, create a top level connectedAndroidTest
        // DefaultTask.
        if (hasFlavors) {
            tasks.create(connectedRootName, AndroidReportTask.class,
                    new Action<AndroidReportTask>() {
                        @Override
                        public void execute(AndroidReportTask mainConnectedTask) {
                            mainConnectedTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                            mainConnectedTask.setDescription("Installs and runs instrumentation "
                                    + "tests for all flavors on connected devices.");
                            mainConnectedTask.setReportType(ReportType.MULTI_FLAVOR);

                            ConventionMappingHelper.map(mainConnectedTask, "resultsDir",
                                    new Callable<File>() {
                                        @Override
                                        public File call() {
                                            final String dir =
                                                    extension.getTestOptions().getResultsDir();
                                            String rootLocation = dir != null && !dir.isEmpty()
                                                    ? dir : defaultResultsDir;
                                            return project.file(rootLocation + "/connected/"
                                                    + FD_FLAVORS_ALL);
                                        }
                                    });
                            ConventionMappingHelper.map(mainConnectedTask, "reportsDir",
                                    new Callable<File>() {
                                        @Override
                                        public File call() {
                                            final String dir =
                                                    extension.getTestOptions().getReportDir();
                                            String rootLocation = dir != null && !dir.isEmpty()
                                                    ? dir : defaultReportsDir;
                                            return project.file(rootLocation + "/connected/"
                                                    + FD_FLAVORS_ALL);
                                        }
                                    });

                        }

                    });
            reportTasks.add(connectedRootName);
        } else {
            tasks.create(connectedRootName, new Action<Task>() {
                @Override
                public void execute(Task connectedTask) {
                    connectedTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                    connectedTask.setDescription(
                            "Installs and runs instrumentation tests for all flavors on connected devices.");
                }

            });
        }

        tasks.named(CONNECTED_CHECK, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(connectedRootName);
            }

        });

        final String mainProviderTaskName = DEVICE + ANDROID_TEST.getSuffix();
        // if more than one provider tasks, either because of several flavors, or because of
        // more than one providers, then create an aggregate report tasks for all of them.
        if (providers.size() > 1 || hasFlavors) {
            tasks.create(mainProviderTaskName, AndroidReportTask.class,
                    new Action<AndroidReportTask>() {
                        @Override
                        public void execute(AndroidReportTask mainProviderTask) {
                            mainProviderTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                            mainProviderTask.setDescription(
                                    "Installs and runs instrumentation tests using all Device Providers.");
                            mainProviderTask.setReportType(ReportType.MULTI_FLAVOR);

                            ConventionMappingHelper.map(mainProviderTask, "resultsDir",
                                    new Callable<File>() {
                                        @Override
                                        public File call() throws Exception {
                                            final String dir =
                                                    extension.getTestOptions().getResultsDir();
                                            String rootLocation =  dir != null && !dir.isEmpty()
                                                    ? dir : defaultResultsDir;

                                            return project.file(rootLocation + "/devices/"
                                                    + FD_FLAVORS_ALL);
                                        }
                                    });
                            ConventionMappingHelper.map(mainProviderTask, "reportsDir",
                                    new Callable<File>() {
                                        @Override
                                        public File call() throws Exception {
                                            final String dir =
                                                    extension.getTestOptions().getReportDir();
                                            String rootLocation =  dir != null && !dir.isEmpty()
                                                    ? dir : defaultReportsDir;
                                            return project.file(rootLocation + "/devices/"
                                                    + FD_FLAVORS_ALL);
                                        }
                                    });
                        }

                    });
            reportTasks.add(mainProviderTaskName);
        } else {
            tasks.create(mainProviderTaskName, new Action<Task>() {
                @Override
                public void execute(Task providerTask) {
                    providerTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                    providerTask.setDescription(
                            "Installs and runs instrumentation tests using all Device Providers.");
                }
            });
        }

        tasks.named(DEVICE_CHECK, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(mainProviderTaskName);
            }
        });

        // Create top level unit test tasks.
        tasks.create(JavaPlugin.TEST_TASK_NAME, new Action<Task>() {
            @Override
            public void execute(Task unitTestTask) {
                unitTestTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                unitTestTask.setDescription("Run unit tests for all variants.");
            }

        });
        tasks.named(JavaBasePlugin.CHECK_TASK_NAME,
                new Action<Task>() {
                    @Override
                    public void execute(Task check) {
                        check.dependsOn(JavaPlugin.TEST_TASK_NAME);
                    }
                });

        // If gradle is launched with --continue, we want to run all tests and generate an
        // aggregate report (to help with the fact that we may have several build variants, or
        // or several device providers).
        // To do that, the report tasks must run even if one of their dependent tasks (flavor
        // or specific provider tasks) fails, when --continue is used, and the report task is
        // meant to run (== is in the task graph).
        // To do this, we make the children tasks ignore their errors (ie they won't fail and
        // stop the build).
        if (!reportTasks.isEmpty() && project.getGradle().getStartParameter()
                .isContinueOnFailure()) {
            project.getGradle().getTaskGraph().whenReady(new Closure<Void>(this, this) {
                public void doCall(TaskExecutionGraph taskGraph) {
                    for (String reportTask : reportTasks) {
                        if (taskGraph.hasTask(reportTask)) {
                            tasks.named(reportTask, new Action<Task>() {
                                @Override
                                public void execute(Task task) {
                                    ((AndroidReportTask) task).setWillRun();
                                }
                            });
                        }
                    }
                }
            });
        }
    }

    protected void createConnectedTestForVariant(
            @NonNull TaskFactory tasks,
            @NonNull final VariantScope variantScope) {
        final BaseVariantData<? extends BaseVariantOutputData> baseVariantData =
                variantScope.getTestedVariantData();
        final TestVariantData testVariantData = (TestVariantData) variantScope.getVariantData();

        // get single output for now
        final BaseVariantOutputData variantOutputData = baseVariantData.getOutputs().get(0);
        final BaseVariantOutputData testVariantOutputData = testVariantData.getOutputs().get(0);

        String connectedRootName = CONNECTED + ANDROID_TEST.getSuffix();

        TestDataImpl testData = new TestDataImpl(testVariantData);
        testData.setExtraInstrumentationTestRunnerArgs(
                AndroidGradleOptions.getExtraInstrumentationTestRunnerArgs(project));

        // create the check tasks for this test
        // first the connected one.
        ImmutableList<Task> artifactsTasks = ImmutableList.of(
                testVariantData.getOutputs().get(0).assembleTask,
                baseVariantData.assembleVariantTask);

        final AndroidTask<DeviceProviderInstrumentTestTask> connectedTask = androidTasks.create(
                tasks,
                new DeviceProviderInstrumentTestTask.ConfigAction(
                        testVariantData.getScope(),
                        new ConnectedDeviceProvider(sdkHandler.getSdkInfo().getAdb(),
                                globalScope.getExtension().getAdbOptions().getTimeOutInMs(),
                                new LoggerWrapper(logger)), testData));

        connectedTask.dependsOn(tasks, artifactsTasks);

        tasks.named(connectedRootName, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(connectedTask.getName());
            }
        });

        if (baseVariantData.getVariantConfiguration().getBuildType().isTestCoverageEnabled()
                && !baseVariantData.getVariantConfiguration().getUseJack()) {
            final AndroidTask<JacocoReportTask> reportTask = androidTasks.create(
                    tasks,
                    variantScope.getTaskName("create", "CoverageReport"),
                    JacocoReportTask.class,
                    new Action<JacocoReportTask>() {
                        @Override
                        public void execute(JacocoReportTask reportTask) {
                            reportTask.setDescription("Creates JaCoCo test coverage report from "
                                    + "data gathered on the device.");

                            reportTask.setReportName(
                                    baseVariantData.getVariantConfiguration().getFullName());

                            ConventionMappingHelper.map(reportTask, "jacocoClasspath",
                                    new Callable<FileCollection>() {
                                        @Override
                                        public FileCollection call() throws Exception {
                                            return project.getConfigurations().getAt(
                                                    JacocoPlugin.ANT_CONFIGURATION_NAME);
                                        }
                                    });
                            ConventionMappingHelper
                                    .map(reportTask, "coverageFile", new Callable<File>() {
                                        @Override
                                        public File call() {
                                            return new File(
                                                    ((TestVariantData) testVariantData.getScope()
                                                            .getVariantData()).connectedTestTask
                                                            .getCoverageDir(),
                                                    SimpleTestCallable.FILE_COVERAGE_EC);
                                        }
                                    });
                            ConventionMappingHelper
                                    .map(reportTask, "classDir", new Callable<File>() {
                                        @Override
                                        public File call() {
                                            return baseVariantData.javacTask.getDestinationDir();
                                        }
                                    });
                            ConventionMappingHelper
                                    .map(reportTask, "sourceDir", new Callable<List<File>>() {
                                        @Override
                                        public List<File> call() {
                                            return baseVariantData
                                                    .getJavaSourceFoldersForCoverage();
                                        }
                                    });

                            ConventionMappingHelper
                                    .map(reportTask, "reportDir", new Callable<File>() {
                                        @Override
                                        public File call() {
                                            return new File(
                                                    variantScope.getGlobalScope().getReportsDir(),
                                                    "/coverage/" + baseVariantData
                                                            .getVariantConfiguration()
                                                            .getDirName());
                                        }
                                    });

                            reportTask.dependsOn(connectedTask.getName());
                        }
                    });

            variantScope.setCoverageReportTask(reportTask);
            baseVariantData.getScope().getCoverageReportTask().dependsOn(tasks, reportTask);

            tasks.named(connectedRootName, new Action<Task>() {
                @Override
                public void execute(Task it) {
                    it.dependsOn(reportTask.getName());
                }
            });
        }

        String mainProviderTaskName = DEVICE + ANDROID_TEST.getSuffix();

        List<DeviceProvider> providers = getExtension().getDeviceProviders();

        boolean hasFlavors = baseVariantData.getVariantConfiguration().hasFlavors();

        // now the providers.
        for (DeviceProvider deviceProvider : providers) {

            final AndroidTask<DeviceProviderInstrumentTestTask> providerTask = androidTasks
                    .create(tasks, new DeviceProviderInstrumentTestTask.ConfigAction(
                            testVariantData.getScope(), deviceProvider, testData));

            tasks.named(mainProviderTaskName, new Action<Task>() {
                @Override
                public void execute(Task it) {
                    it.dependsOn(providerTask.getName());
                }
            });
            providerTask.dependsOn(tasks, artifactsTasks);
        }

        // now the test servers
        List<TestServer> servers = getExtension().getTestServers();
        for (TestServer testServer : servers) {
            final TestServerTask serverTask = project.getTasks().create(
                    hasFlavors ?
                            baseVariantData.getScope().getTaskName(testServer.getName() + "Upload")
                            :
                                    testServer.getName() + ("Upload"),
                    TestServerTask.class);

            serverTask.setDescription(
                    "Uploads APKs for Build \'" + baseVariantData.getVariantConfiguration()
                            .getFullName() + "\' to Test Server \'" +
                            StringHelper.capitalize(testServer.getName()) + "\'.");
            serverTask.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
            serverTask.setVariantName(
                    baseVariantData.getScope().getVariantConfiguration().getFullName());
            serverTask.dependsOn(testVariantOutputData.assembleTask,
                    variantOutputData.assembleTask);

            serverTask.setTestServer(testServer);

            ConventionMappingHelper.map(serverTask, "testApk", new Callable<File>() {
                @Override
                public File call() throws Exception {
                    return testVariantOutputData.getOutputFile();
                }
            });
            if (!(baseVariantData instanceof LibraryVariantData)) {
                ConventionMappingHelper.map(serverTask, "testedApk", new Callable<File>() {
                    @Override
                    public File call() throws Exception {
                        return variantOutputData.getOutputFile();
                    }
                });
            }

            ConventionMappingHelper.map(serverTask, "variantName", new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return baseVariantData.getVariantConfiguration().getFullName();
                }
            });

            tasks.named(DEVICE_CHECK, new Action<Task>() {
                @Override
                public void execute(Task it) {
                    it.dependsOn(serverTask);
                }
            });

            if (!testServer.isConfigured()) {
                serverTask.setEnabled(false);
            }
        }
    }

    public void createJarTasks(@NonNull TaskFactory tasks, @NonNull final VariantScope scope) {
        final BaseVariantData variantData = scope.getVariantData();

        final GradleVariantConfiguration config = variantData.getVariantConfiguration();
        tasks.create(
                scope.getTaskName("jar", "Classes"),
                AndroidJarTask.class,
                new Action<AndroidJarTask>() {
                    @Override
                    public void execute(AndroidJarTask jarTask) {
                        jarTask.setArchiveName("classes.jar");
                        jarTask.setDestinationDir(new File(
                                scope.getGlobalScope().getIntermediatesDir(),
                                "packaged/" + config.getDirName() + "/"));
                        jarTask.from(scope.getJavaOutputDir());
                        jarTask.dependsOn(scope.getJavacTask().getName());
                        variantData.binaryFileProviderTask = jarTask;
                    }

                });
    }

    /**
     * Creates the post-compilation tasks for the given Variant.
     *
     * These tasks create the dex file from the .class files, plus optional intermediary steps like
     * proguard and jacoco
     *
     */
    public void createPostCompilationTasks(@NonNull TaskFactory tasks,
            @NonNull final VariantScope variantScope) {

        checkNotNull(variantScope.getJavacTask());

        final ApkVariantData variantData = (ApkVariantData) variantScope.getVariantData();
        final GradleVariantConfiguration config = variantData.getVariantConfiguration();

        TransformManager transformManager = variantScope.getTransformManager();

        // ---- Code Coverage first -----
        boolean isTestCoverageEnabled = config.getBuildType().isTestCoverageEnabled() &&
                !config.getType().isForTesting() &&
                getIncrementalMode(variantScope.getVariantConfiguration()) == IncrementalMode.NONE;
        if (isTestCoverageEnabled) {
            createJacocoTransform(tasks, variantScope);
        }

        boolean isMinifyEnabled = config.isMinifyEnabled();
        boolean isMultiDexEnabled = config.isMultiDexEnabled();
        // Switch to native multidex if possible when using instant run.
        boolean isLegacyMultiDexMode = config.isLegacyMultiDexMode() &&
                (getIncrementalMode(variantScope.getVariantConfiguration()) == IncrementalMode.NONE
                        || variantScope.getInstantRunBuildContext().getPatchingPolicy() ==
                                InstantRunPatchingPolicy.PRE_LOLLIPOP);

        AndroidConfig extension = variantScope.getGlobalScope().getExtension();

        // ----- External Transforms -----
        // apply all the external transforms.
        List<Transform> customTransforms = extension.getTransforms();
        List<List<Object>> customTransformsDependencies = extension.getTransformsDependencies();

        for (int i = 0, count = customTransforms.size() ; i < count ; i++) {
            Transform transform = customTransforms.get(i);
            AndroidTask<TransformTask> task = transformManager
                    .addTransform(tasks, variantScope, transform);
            // task could be null if the transform is invalid.
            if (task != null) {
                List<Object> deps = customTransformsDependencies.get(i);
                if (!deps.isEmpty()) {
                    task.dependsOn(tasks, deps);
                }

                // if the task is a no-op then we make assemble task depend on it.
                if (transform.getScopes().isEmpty()) {
                    variantData.assembleVariantTask.dependsOn(tasks, task);
                }

            }
        }

        // ----- Minify next -----

        if (isMinifyEnabled) {
            boolean outputToJarFile = isMultiDexEnabled && isLegacyMultiDexMode;
            createMinifyTransform(tasks, variantScope, outputToJarFile);
        }

        // ----- 10x support

        AndroidTask<InstantRunWrapperTask> incrementalBuildWrapperTask = null;
        if (getIncrementalMode(variantScope.getVariantConfiguration()) != IncrementalMode.NONE) {

            variantScope.getInstantRunBuildContext().setInstantRunMode(true);

            // we are creating two anchor tasks
            // 1. allActionAnchorTask to anchor tasks that should be executed whether a full build or
            //    incremental build is invoked.
            // 2. incrementalAnchorTask to anchor tasks that should only be executed when an
            //    incremental build is requested.
            // the incrementalAnchorTask will therefore depend on the allActionAnchorTask.

            AndroidTask<InstantRunAnchorTask> allActionsAnchorTask =
                    createInstantRunAllActionsTasks(tasks, variantScope);
            variantScope.setInstantRunAnchorTask(allActionsAnchorTask);
            incrementalBuildWrapperTask = createInstantRunIncrementalTasks(tasks, variantScope);
            variantScope.setInstantRunIncrementalTask(incrementalBuildWrapperTask);
            incrementalBuildWrapperTask.dependsOn(tasks, allActionsAnchorTask);

            // when dealing with platforms that can handle multi dexes natively, automatically
            // turn on multi dexing so shards are packaged as individual dex files.
            if (InstantRunPatchingPolicy.PRE_LOLLIPOP !=
                    variantScope.getInstantRunBuildContext().getPatchingPolicy()) {
                isMultiDexEnabled = true;
                // force pre-dexing to be true as we rely on individual slices to be packaged
                // separately.
                extension.getDexOptions().setPreDexLibraries(true);
            }

            extension.getDexOptions().setJumboMode(true);
        }
        // ----- Multi-Dex support

        AndroidTask<TransformTask> multiDexClassListTask = null;
        // non Library test are running as native multi-dex
        if (isMultiDexEnabled && isLegacyMultiDexMode) {
            if (!variantData.getVariantConfiguration().getBuildType().isUseProguard()) {
                throw new IllegalStateException(
                        "Build-in class shrinker and multidex are not supported yet.");
            }

            // ----------
            // create a transform to jar the inputs into a single jar.
            if (!isMinifyEnabled) {
                // merge the classes only, no need to package the resources since they are
                // not used during the computation.
                JarMergingTransform jarMergingTransform = new JarMergingTransform(
                        TransformManager.SCOPE_FULL_PROJECT);
                transformManager.addTransform(tasks, variantScope, jarMergingTransform);
            }

            // ----------
            // Create a task to collect the list of manifest entry points which are
            // needed in the primary dex
            AndroidTask<CreateManifestKeepList> manifestKeepListTask = androidTasks.create(tasks,
                    new CreateManifestKeepList.ConfigAction(variantScope));
            manifestKeepListTask.dependsOn(tasks,
                    variantData.getOutputs().get(0).getScope().getManifestProcessorTask());

            // ---------
            // create the transform that's going to take the code and the proguard keep list
            // from above and compute the main class list.
            MultiDexTransform multiDexTransform = new MultiDexTransform(
                    variantScope.getManifestKeepListFile(),
                    variantScope,
                    null);
            multiDexClassListTask = transformManager.addTransform(
                    tasks, variantScope, multiDexTransform);
            multiDexClassListTask.dependsOn(tasks, manifestKeepListTask);
        }
        // create dex transform
        DexTransform dexTransform = new DexTransform(
                extension.getDexOptions(),
                config.getBuildType().isDebuggable(),
                isMultiDexEnabled,
                isMultiDexEnabled && isLegacyMultiDexMode ? variantScope.getMainDexListFile() : null,
                variantScope.getPreDexOutputDir(),
                variantScope.getGlobalScope().getAndroidBuilder(),
                getLogger(),
                variantScope.getInstantRunBuildContext());
        AndroidTask<TransformTask> dexTask = transformManager.addTransform(
                tasks, variantScope, dexTransform);
        // need to manually make dex task depend on MultiDexTransform since there's no stream
        // consumption making this automatic
        dexTask.optionalDependsOn(tasks, multiDexClassListTask);

        // if we are in instant-run mode and the patching policy is relying on mult-dex shards,
        // we should run the dexing as part of the incremental build.
        if (incrementalBuildWrapperTask != null &&
                InstantRunPatchingPolicy.PRE_LOLLIPOP !=
                    variantScope.getInstantRunBuildContext().getPatchingPolicy()) {
            incrementalBuildWrapperTask.dependsOn(tasks, dexTask);
        }
    }

    /**
     * Create InstantRun related tasks that should be ran right after the java compilation task.
     */
    @NonNull
    protected AndroidTask<InstantRunAnchorTask> createInstantRunAllActionsTasks(
            @NonNull TaskFactory tasks, @NonNull VariantScope variantScope) {

        AndroidTask<InstantRunAnchorTask> allActionAnchorTask = getAndroidTasks().create(tasks,
                new InstantRunAnchorTask.ConfigAction(variantScope, "Tasks"));

        AndroidTask<BuildInfoLoaderTask> buildInfoLoaderTask = getAndroidTasks().create(tasks,
                new BuildInfoLoaderTask.ConfigAction(variantScope, getLogger()));

        TransformManager transformManager = variantScope.getTransformManager();

        ExtractJarsTransform extractJarsTransform = new ExtractJarsTransform(
                ImmutableSet.<QualifiedContent.ContentType>of(
                        QualifiedContent.DefaultContentType.CLASSES),
                ImmutableSet.of(Scope.SUB_PROJECTS));
        AndroidTask<TransformTask> extractJarsTask = transformManager
                .addTransform(tasks, variantScope, extractJarsTransform);

        // always run the verifier first, since if it detects incompatible changes, we
        // should skip bytecode enhancements of the changed classes.
        InstantRunVerifierTransform verifierTransform = new InstantRunVerifierTransform(variantScope);
        AndroidTask<TransformTask> verifierTask = variantScope.getTransformManager()
                .addTransform(tasks, variantScope, verifierTransform);
        verifierTask.dependsOn(tasks, extractJarsTask);
        variantScope.setInstantRunVerifierTask(verifierTask);

        NoChangesVerifierTransform jniLibsVerifierTransform = new NoChangesVerifierTransform(
                variantScope,
                ImmutableSet.<ContentType>of(DefaultContentType.RESOURCES, ExtendedContentType.NATIVE_LIBS),
                getResMergingScopes(variantScope), InstantRunVerifierStatus.JAVA_RESOURCES_CHANGED,
                true /* abortBuild */);
        AndroidTask<TransformTask> jniLibsVerifierTask =
                variantScope.getTransformManager().addTransform(
                        tasks,
                        variantScope,
                        jniLibsVerifierTransform);
        jniLibsVerifierTask.dependsOn(tasks, verifierTask);

        NoChangesVerifierTransform dependenciesVerifierTransform =
                new NoChangesVerifierTransform(
                        variantScope,
                        ImmutableSet.<ContentType>of(DefaultContentType.CLASSES),
                        Sets.immutableEnumSet(
                                Scope.PROJECT_LOCAL_DEPS,
                                Scope.SUB_PROJECTS_LOCAL_DEPS,
                                Scope.EXTERNAL_LIBRARIES),
                        InstantRunVerifierStatus.DEPENDENCY_CHANGED,
                        false /* abortBuild */);
        AndroidTask<TransformTask> dependenciesVerifierTask =
                variantScope.getTransformManager().addTransform(
                        tasks,
                        variantScope,
                        dependenciesVerifierTransform);
        dependenciesVerifierTask.dependsOn(tasks, verifierTask);


        InstantRunTransform instantRunTransform = new InstantRunTransform(variantScope);
        AndroidTask<TransformTask> instantRunTask = transformManager
                .addTransform(tasks, variantScope, instantRunTransform);
        instantRunTask.dependsOn(tasks, buildInfoLoaderTask, verifierTask, jniLibsVerifierTask,
                dependenciesVerifierTask);

        AndroidTask<FastDeployRuntimeExtractorTask> extractorTask = getAndroidTasks().create(
                tasks, new FastDeployRuntimeExtractorTask.ConfigAction(variantScope));

        // also add a new stream for the extractor task output.
        variantScope.getTransformManager().addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_CLASS)
                .addScope(Scope.EXTERNAL_LIBRARIES)
                .setJar(variantScope.getIncrementalRuntimeSupportJar())
                .setDependency(extractorTask.get(tasks))
                .build());

        AndroidTask<GenerateInstantRunAppInfoTask> generateAppInfoAndroidTask = getAndroidTasks()
                .create(tasks, new GenerateInstantRunAppInfoTask.ConfigAction(variantScope));

        // create the AppInfo.class for this variant.
        GenerateInstantRunAppInfoTask generateInstantRunAppInfoTask
                = generateAppInfoAndroidTask.get(tasks);

        // make the task that generates the AppInfo dependent on the first merge manifest task
        // so we can get its output file.
        VariantOutputScope outputScope =
                variantScope.getVariantData().getOutputs().get(0).getScope();
        generateAppInfoAndroidTask.dependsOn(tasks, outputScope.getManifestProcessorTask());

        // also add a new stream for the injector task output.
        variantScope.getTransformManager().addStream(OriginalStream.builder()
                .addContentTypes(TransformManager.CONTENT_CLASS)
                .addScope(Scope.EXTERNAL_LIBRARIES)
                .setJar(generateInstantRunAppInfoTask.getOutputFile())
                .setDependency(generateInstantRunAppInfoTask)
                .build());

        allActionAnchorTask.dependsOn(tasks, instantRunTask);

        DexOptions dexOptions = variantScope.getGlobalScope().getExtension().getDexOptions();

        // we always produce the reload.dex irrespective of the targeted version,
        // and if we are not in incremental mode, we need to still need to clean our output state.
        InstantRunDex reloadDexTransform = new InstantRunDex(
                variantScope,
                InstantRunBuildType.RELOAD,
                androidBuilder,
                dexOptions,
                getLogger(),
                ImmutableSet.<ContentType>of(
                        ExtendedContentType.CLASSES_ENHANCED));

        AndroidTask<TransformTask> reloadDexing = variantScope.getTransformManager()
                .addTransform(tasks, variantScope, reloadDexTransform);

        allActionAnchorTask.dependsOn(tasks, reloadDexing);

        return allActionAnchorTask;
    }

    /**
     * Creates all InstantRun related transforms after compilation.
     */
    @NonNull
    public AndroidTask<InstantRunWrapperTask> createInstantRunIncrementalTasks(
            @NonNull TaskFactory tasks, @NonNull final VariantScope scope) {

        // we are creating two anchor tasks
        // 1. allActionAnchorTask to anchor tasks that should be executed whether a full build or
        //    incremental build is invoked.
        // 2. incrementalAnchorTask to anchor tasks that should only be executed when an
        //    incremental build is requested.
        // the incrementalAnchorTask will therefore depend on the allActionAnchorTask.
        AndroidTask<InstantRunAnchorTask> incrementalAnchorTask = androidTasks.create(tasks,
                new InstantRunAnchorTask.ConfigAction(scope));

        // create the incremental version of the build-info.xml, another task will take care
        // of generating the build-info.xml when a full build is invoked.
        AndroidTask<InstantRunWrapperTask> incrementalWrapperTask = getAndroidTasks().create(tasks,
                new InstantRunWrapperTask.ConfigAction(
                        scope, InstantRunWrapperTask.TaskType.INCREMENTAL, getLogger()));

        // this will force build-info.xml to be generated only when the external task is directly
        // invoked by the IDE.
        incrementalAnchorTask.dependsOn(tasks, incrementalWrapperTask);

        scope.getInstantRunBuildContext().setApiLevel(
                AndroidGradleOptions.getTargetApiLevel(project),
                AndroidGradleOptions.getColdswapMode(project),
                AndroidGradleOptions.getBuildTargetAbi(project));
        scope.getInstantRunBuildContext().setDensity(
                AndroidGradleOptions.getBuildTargetDensity(project));
        InstantRunPatchingPolicy patchingPolicy =
                scope.getInstantRunBuildContext().getPatchingPolicy();

        DexOptions dexOptions = scope.getGlobalScope().getExtension().getDexOptions();

        // let's create the coldswap kicker task. It is necessary as sometimes the IDE will
        // request an assembleDebug to get the latest coldswap bits yet without any user changes.
        // so we need to manually kick the tasks that accumulated changes during reload.dex
        // iterations so they produce the artifacts.
        AndroidTask<ColdswapArtifactsKickerTask> coldswapKickerTask = getAndroidTasks().create(
                tasks, new ColdswapArtifactsKickerTask.ConfigAction("coldswapKicker", scope));

        // this kicker task is dependent on the verifier and associated tasks result.
        coldswapKickerTask.dependsOn(tasks, scope.getInstantRunVerifierTask());

        if (patchingPolicy == InstantRunPatchingPolicy.PRE_LOLLIPOP) {
            // for Dalvik, we generate a restart.dex.
            InstantRunDex classesTwoTransform = new InstantRunDex(
                    scope,
                    InstantRunBuildType.RESTART,
                    androidBuilder,
                    dexOptions,
                    getLogger(),
                    ImmutableSet.<ContentType>of(
                            DefaultContentType.CLASSES));
            AndroidTask<TransformTask> classesTwoDexing = scope.getTransformManager()
                    .addTransform(tasks, scope, classesTwoTransform);
            // restart task depends on the kicker result
            classesTwoDexing.dependsOn(tasks, coldswapKickerTask);
            incrementalWrapperTask.dependsOn(tasks, classesTwoDexing);
        } else {
            // if we are at API 21 or above, we generate multi-dexes.
            // this transform and all its dependencies will also run in full build mode as
            // it is automatically enrolled by the transform manager.
            InstantRunSlicer slicer = new InstantRunSlicer(getLogger(), scope);
            AndroidTask<TransformTask> slicing = scope.getTransformManager()
                    .addTransform(tasks, scope, slicer);

            // slicing should only happen if we need to produce the restart dexes.
            slicing.dependsOn(tasks, coldswapKickerTask);

            incrementalWrapperTask.dependsOn(tasks, slicing);

        }

        return incrementalWrapperTask;
    }

    protected void handleJacocoDependencies(@NonNull VariantScope variantScope) {
        GradleVariantConfiguration config = variantScope.getVariantConfiguration();
        // we add the jacoco jar if coverage is enabled, but we don't add it
        // for test apps as it's already part of the tested app.
        // For library project, since we cannot use the local jars of the library,
        // we add it as well.
        boolean isTestCoverageEnabled = config.getBuildType().isTestCoverageEnabled() &&
                getIncrementalMode(variantScope.getVariantConfiguration()) == IncrementalMode.NONE &&
                (!config.getType().isForTesting() ||
                        (config.getTestedConfig() != null &&
                                config.getTestedConfig().getType() == VariantType.LIBRARY));
        if (isTestCoverageEnabled) {
            Copy agentTask = getJacocoAgentTask();

            // also add a new stream for the jacoco agent Jar
            variantScope.getTransformManager().addStream(OriginalStream.builder()
                    .addContentTypes(TransformManager.CONTENT_JARS)
                    .addScope(Scope.EXTERNAL_LIBRARIES)
                    .setJar(new File(agentTask.getDestinationDir(), FILE_JACOCO_AGENT))
                    .setDependency(agentTask)
                    .build());
        }
    }

    public void createJacocoTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull final VariantScope variantScope) {

        AndroidTask<?> task = variantScope.getTransformManager().addTransform(taskFactory,
                variantScope, new JacocoTransform(project.getConfigurations()));

        Copy agentTask = getJacocoAgentTask();
        task.dependsOn(taskFactory, agentTask);
    }

    public void createJackTask(
            @NonNull TaskFactory tasks,
            @NonNull VariantScope scope) {

        // ----- Create Jill tasks -----
        final AndroidTask<JillTask> jillRuntimeTask = androidTasks.create(tasks,
                new JillTask.RuntimeTaskConfigAction(scope));

        final AndroidTask<JillTask> jillPackagedTask = androidTasks.create(tasks,
                new JillTask.PackagedConfigAction(scope));

        jillPackagedTask.dependsOn(tasks,
                scope.getVariantData().getVariantDependency().getPackageConfiguration()
                        .getBuildDependencies());

        // ----- Create Jack Task -----
        AndroidTask<JackTask> jackTask = androidTasks.create(tasks,
                new JackTask.ConfigAction(scope, isVerbose(), isDebugLog()));

        // Jack is compiling and also providing the binary and mapping files.
        setJavaCompilerTask(jackTask, tasks, scope);
        jackTask.optionalDependsOn(tasks, scope.getMergeJavaResourcesTask());
        jackTask.dependsOn(tasks,
                scope.getVariantData().sourceGenTask,
                jillRuntimeTask,
                jillPackagedTask,
                // TODO - dependency information for the compile classpath is being lost.
                // Add a temporary approximation
                scope.getVariantData().getVariantDependency().getCompileConfiguration()
                        .getBuildDependencies());

    }

    protected void createDataBindingTasks(@NonNull TaskFactory tasks, @NonNull VariantScope scope) {
        if (scope.getVariantConfiguration().getUseJack()) {
            androidBuilder.getErrorReporter().handleSyncError(
                    scope.getVariantConfiguration().getFullName(),
                    SyncIssue.TYPE_JACK_IS_NOT_SUPPORTED,
                    "Data Binding does not support Jack builds yet"
            );
        }

        dataBindingBuilder.setDebugLogEnabled(getLogger().isDebugEnabled());
        AndroidTask<DataBindingProcessLayoutsTask> processLayoutsTask = androidTasks
                .create(tasks, new DataBindingProcessLayoutsTask.ConfigAction(scope));
        scope.setDataBindingProcessLayoutsTask(processLayoutsTask);

        scope.getGenerateRClassTask().dependsOn(tasks, processLayoutsTask);
        processLayoutsTask.dependsOn(tasks, scope.getMergeResourcesTask());

        AndroidTask<DataBindingExportBuildInfoTask> exportBuildInfo = androidTasks
                .create(tasks, new DataBindingExportBuildInfoTask.ConfigAction(scope,
                        dataBindingBuilder.getPrintMachineReadableOutput()));
        scope.setDataBindingExportInfoTask(exportBuildInfo);

        exportBuildInfo.dependsOn(tasks, processLayoutsTask);
        AndroidTask<? extends AbstractCompile> javaCompilerTask = scope.getJavaCompilerTask();
        if (javaCompilerTask != null) {
            javaCompilerTask.dependsOn(tasks, exportBuildInfo);
        }

        setupCompileTaskDependencies(tasks, scope, scope.getVariantData(), exportBuildInfo);
    }

    /**
     * Creates the final packaging task, and optionally the zipalign task (if the variant is signed)
     *
     * @param publishApk if true the generated APK gets published.
     * @param fullBuildInfoGeneratorTask task that generates the build-info.xml for full build.
     */
    public void createPackagingTask(@NonNull TaskFactory tasks,
            @NonNull VariantScope variantScope,
            boolean publishApk,
            @Nullable AndroidTask<InstantRunWrapperTask> fullBuildInfoGeneratorTask) {

        final ApkVariantData variantData = (ApkVariantData) variantScope.getVariantData();
        GradleVariantConfiguration config = variantData.getVariantConfiguration();

        boolean signedApk = variantData.isSigned();
        final File apkLocation = new File(variantScope.getGlobalScope().getApkLocation());

        boolean multiOutput = variantData.getOutputs().size() > 1;

        GradleVariantConfiguration variantConfiguration = variantScope.getVariantConfiguration();
        /**
         * PrePackaging step class that will look if the packaging of the main APK split is
         * necessary when running in InstantRun mode. In InstantRun mode targeting an api 23 or
         * above device, resources are packaged in the main split APK. However when a warm swap is
         * possible, it is not necessary to produce immediately the new main SPLIT since the runtime
         * use the resources.ap_ file directly. However, as soon as an incompatible change forcing a
         * cold swap is triggered, the main APK must be rebuilt (even if the resources were changed
         * in a previous build).
         */
        AndroidTask<PrePackageApplication> prePackageApp = androidTasks.create(tasks,
                new PrePackageApplication.ConfigAction("prePackageMarkerFor", variantScope));
        IncrementalMode incrementalMode = getIncrementalMode(variantConfiguration);
        if (incrementalMode != IncrementalMode.NONE) {
            prePackageApp.dependsOn(tasks, variantScope.getInstantRunAnchorTask());
        }

        // loop on all outputs. The only difference will be the name of the task, and location
        // of the generated data.
        for (final ApkVariantOutputData variantOutputData : variantData.getOutputs()) {
            VariantOutputScope variantOutputScope = variantOutputData.getScope();

            final String outputName = variantOutputData.getFullName();
            InstantRunPatchingPolicy instantRunPatchingPolicy =
                    variantScope.getInstantRunBuildContext().getPatchingPolicy();

            // when building for instant run, never puts the user's code in the APK directly.
            PackageApplication.DexPackagingPolicy dexPackagingPolicy =
                     incrementalMode == IncrementalMode.NONE
                            || variantScope.getInstantRunBuildContext().getPatchingPolicy()
                                    == InstantRunPatchingPolicy.PRE_LOLLIPOP
                            ? PackageApplication.DexPackagingPolicy.STANDARD
                            : PackageApplication.DexPackagingPolicy.INSTANT_RUN;

            AndroidTask<PackageApplication> packageApp = androidTasks.create(tasks,
                    new PackageApplication.ConfigAction(variantOutputScope, dexPackagingPolicy));

            packageApp.dependsOn(tasks, prePackageApp, variantOutputScope.getProcessResourcesTask());

            packageApp.optionalDependsOn(
                    tasks,
                    variantOutputScope.getShrinkResourcesTask(),
                    // TODO: When Jack is converted, add activeDexTask to VariantScope.
                    variantOutputScope.getVariantScope().getJavaCompilerTask(),
                    // TODO: Remove when Jack is converted to AndroidTask.
                    variantData.javaCompilerTask,
                    variantOutputData.packageSplitResourcesTask,
                    variantOutputData.packageSplitAbiTask);

            TransformManager transformManager = variantScope.getTransformManager();

            for (TransformStream stream : transformManager
                    .getStreams(PackageApplication.sDexFilter)) {
                // TODO Optimize to avoid creating too many actions
                packageApp.dependsOn(tasks, stream.getDependencies());
            }

            for (TransformStream stream : transformManager.getStreams(
                    PackageApplication.sResFilter)) {
                // TODO Optimize to avoid creating too many actions
                packageApp.dependsOn(tasks, stream.getDependencies());
            }
            for (TransformStream stream : transformManager.getStreams(
                    PackageApplication.sNativeLibsFilter)) {
                // TODO Optimize to avoid creating too many actions
                packageApp.dependsOn(tasks, stream.getDependencies());
            }

            AndroidTask appTask = packageApp;

            if (signedApk) {
                if (variantData.getZipAlignEnabled()) {
                    AndroidTask<ZipAlign> zipAlignTask = androidTasks.create(
                            tasks, new ZipAlign.ConfigAction(variantOutputScope));
                    zipAlignTask.dependsOn(tasks, packageApp);
                    if (variantOutputScope.getSplitZipAlignTask() != null) {
                        zipAlignTask.dependsOn(tasks, variantOutputScope.getSplitZipAlignTask());
                    }

                    appTask = zipAlignTask;
                }

            }

            checkState(variantData.assembleVariantTask != null);
            if (fullBuildInfoGeneratorTask != null) {
                fullBuildInfoGeneratorTask.dependsOn(tasks, appTask);
                variantData.assembleVariantTask.dependsOn(fullBuildInfoGeneratorTask.getName());
            }

            // when dealing with 23 and above, we should make sure the packaging task is running
            // as part of the incremental build in case resources have changed and need to be
            // repackaged in the main APK.
            if (dexPackagingPolicy == PackageApplication.DexPackagingPolicy.INSTANT_RUN
                    && instantRunPatchingPolicy == InstantRunPatchingPolicy.MULTI_APK) {
                variantScope.getInstantRunIncrementalTask().dependsOn(tasks, appTask);
            }

            // Add an assemble task
            if (multiOutput) {
                // create a task for this output
                variantOutputData.assembleTask = createAssembleTask(variantOutputData);

                // variant assemble task depends on each output assemble task.
                variantData.assembleVariantTask.dependsOn(variantOutputData.assembleTask);
            } else {
                // single output
                variantOutputData.assembleTask = variantData.assembleVariantTask;
            }

            if (!signedApk && variantOutputData.packageSplitResourcesTask != null) {
                // in case we are not signing the resulting APKs and we have some pure splits
                // we should manually copy them from the intermediate location to the final
                // apk location unmodified.
                final String appTaskName = appTask.getName();
                AndroidTask<Copy> copySplitTask = androidTasks.create(
                        tasks,
                        variantOutputScope.getTaskName("copySplit"),
                        Copy.class,
                        new Action<Copy>() {
                            @Override
                            public void execute(Copy copyTask) {
                                copyTask.setDestinationDir(apkLocation);
                                copyTask.from(variantOutputData.packageSplitResourcesTask.getOutputDirectory());
                                variantOutputData.assembleTask.dependsOn(copyTask);
                                copyTask.mustRunAfter(appTaskName);
                            }
                        });
                variantOutputData.assembleTask.dependsOn(copySplitTask.getName());
            }
            variantOutputData.assembleTask.dependsOn(appTask.getName());

            if (publishApk) {
                final String projectBaseName = globalScope.getProjectBaseName();

                // if this variant is the default publish config or we also should publish non
                // defaults, proceed with declaring our artifacts.
                if (getExtension().getDefaultPublishConfig().equals(outputName)) {
                    appTask.configure(tasks, new Action<Task>() {
                        @Override
                        public void execute(Task packageTask) {
                            project.getArtifacts().add("default",
                                    new ApkPublishArtifact(projectBaseName,
                                            null,
                                            (FileSupplier) packageTask));
                        }

                    });

                    for (FileSupplier outputFileProvider :
                            variantOutputData.getSplitOutputFileSuppliers()) {
                        project.getArtifacts().add("default",
                                new ApkPublishArtifact(projectBaseName, null, outputFileProvider));
                    }

                    try {
                        if (variantOutputData.getMetadataFile() != null) {
                            project.getArtifacts().add("default-metadata",
                                    new MetadataPublishArtifact(projectBaseName, null,
                                            variantOutputData.getMetadataFile()));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    if (variantData.getMappingFileProvider() != null) {
                        project.getArtifacts().add("default-mapping",
                                new MappingPublishArtifact(projectBaseName, null,
                                        variantData.getMappingFileProvider()));
                    }
                }

                if (getExtension().getPublishNonDefault()) {
                    appTask.configure(tasks, new Action<Task>() {
                        @Override
                        public void execute(Task packageTask) {
                            project.getArtifacts().add(
                                    variantData.getVariantDependency().getPublishConfiguration().getName(),
                                    new ApkPublishArtifact(
                                            projectBaseName,
                                            null,
                                            (FileSupplier) packageTask));
                        }

                    });

                    for (FileSupplier outputFileProvider :
                            variantOutputData.getSplitOutputFileSuppliers()) {
                        project.getArtifacts().add(
                                variantData.getVariantDependency().getPublishConfiguration().getName(),
                                new ApkPublishArtifact(
                                        projectBaseName,
                                        null,
                                        outputFileProvider));
                    }

                    try {
                        if (variantOutputData.getMetadataFile() != null) {
                            project.getArtifacts().add(
                                    variantData.getVariantDependency().getMetadataConfiguration().getName(),
                                    new MetadataPublishArtifact(
                                            projectBaseName,
                                            null,
                                            variantOutputData.getMetadataFile()));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    if (variantData.getMappingFileProvider() != null) {
                        project.getArtifacts().add(
                                variantData.getVariantDependency().getMappingConfiguration().getName(),
                                new MappingPublishArtifact(
                                        projectBaseName,
                                        null,
                                        variantData.getMappingFileProvider()));
                    }

                    if (variantData.classesJarTask != null) {
                        project.getArtifacts().add(
                                variantData.getVariantDependency().getClassesConfiguration().getName(),
                                variantData.classesJarTask);
                    }
                }
            }
        }


        // create install task for the variant Data. This will deal with finding the
        // right output if there are more than one.
        // Add a task to install the application package
        if (signedApk) {
            AndroidTask<InstallVariantTask> installTask = androidTasks.create(
                    tasks, new InstallVariantTask.ConfigAction(variantScope));
            installTask.dependsOn(tasks, variantData.assembleVariantTask);
        }

        if (getExtension().getLintOptions().isCheckReleaseBuilds()
                && incrementalMode == IncrementalMode.NONE) {
            createLintVitalTask(variantData);
        }

        // add an uninstall task
        final AndroidTask<UninstallTask> uninstallTask = androidTasks.create(
                tasks, new UninstallTask.ConfigAction(variantScope));

        tasks.named(UNINSTALL_ALL, new Action<Task>() {
            @Override
            public void execute(Task it) {
                it.dependsOn(uninstallTask.getName());
            }
        });
    }

    public Task createAssembleTask(@NonNull final BaseVariantOutputData variantOutputData) {
        Task assembleTask =
                project.getTasks().create(variantOutputData.getScope().getTaskName("assemble"));
        return assembleTask;
    }

    public Task createAssembleTask(TaskFactory tasks,
            @NonNull final BaseVariantData<? extends BaseVariantOutputData> variantData) {
        Task assembleTask =
                project.getTasks().create(variantData.getScope().getTaskName("assemble"));
        return assembleTask;
    }

    public Copy getJacocoAgentTask() {
        if (jacocoAgentTask == null) {
            jacocoAgentTask = project.getTasks().create("unzipJacocoAgent", Copy.class);
            jacocoAgentTask.from(new Callable<List<FileTree>>() {
                @Override
                public List<FileTree> call() throws Exception {
                    return Lists.newArrayList(Iterables.transform(
                            project.getConfigurations().getByName(
                                    JacocoPlugin.AGENT_CONFIGURATION_NAME),
                            new Function<Object, FileTree>() {
                                @Override
                                public FileTree apply(@Nullable Object it) {
                                    return project.zipTree(it);
                                }
                            }));
                }
            });
            jacocoAgentTask.include(FILE_JACOCO_AGENT);
            jacocoAgentTask.into(new File(getGlobalScope().getIntermediatesDir(), "jacoco"));
        }

        return jacocoAgentTask;
    }

    /**
     * creates a zip align. This does not use convention mapping, and is meant to let other plugin
     * create zip align tasks.
     *
     * @param name          the name of the task
     * @param buildContext  the InstantRun build context
     * @param inputFile     the input file
     * @param outputFile    the output file
     * @return the task
     */
    @NonNull
    public ZipAlign createZipAlignTask(
            @NonNull String name,
            @NonNull InstantRunBuildContext buildContext,
            @NonNull File inputFile,
            @NonNull File outputFile) {
        // Add a task to zip align application package
        ZipAlign zipAlignTask = project.getTasks().create(name, ZipAlign.class);

        zipAlignTask.setInputFile(inputFile);
        zipAlignTask.setOutputFile(outputFile);
        zipAlignTask.setInstantRunBuildContext(buildContext);
        ConventionMappingHelper.map(zipAlignTask, "zipAlignExe", new Callable<File>() {
            @Override
            public File call() throws Exception {
                final TargetInfo info = androidBuilder.getTargetInfo();
                if (info != null) {
                    String path = info.getBuildTools().getPath(ZIP_ALIGN);
                    if (path != null) {
                        return new File(path);
                    }
                }

                return null;
            }
        });

        return zipAlignTask;
    }

    protected void createMinifyTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull final VariantScope variantScope,
            boolean createJarFile) {
        doCreateMinifyTransform(taskFactory,
                variantScope,
                null /*mappingConfiguration*/, // No mapping in non-test modules.
                createJarFile);
    }

    /**
     * Actually creates the minify transform, using the given mapping configuration. The mapping is
     * only used by test-only modules.
     */
    protected final void doCreateMinifyTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull final VariantScope variantScope,
            @Nullable Configuration mappingConfiguration,
            boolean createJarFile) {
        if (variantScope.getVariantData().getVariantConfiguration().getBuildType().isUseProguard()) {
            if (getIncrementalMode(variantScope.getVariantConfiguration()) != IncrementalMode.NONE) {
                logger.warn("Instant Run: Proguard is not compatible with instant run. "
                        + "It has been disabled for {}",
                        variantScope.getVariantConfiguration().getFullName());
                return;
            }
            createProguardTransform(taskFactory, variantScope, mappingConfiguration, createJarFile);
            createShrinkResourcesTransform(taskFactory, variantScope);
        } else {
            // Since the built-in class shrinker does not obfuscate, there's no point running
            // it on the test APK (it also doesn't have a -dontshrink mode).
            if (variantScope.getTestedVariantData() == null) {
                createNewShrinkerTransform(variantScope, taskFactory);
                createShrinkResourcesTransform(taskFactory, variantScope);
            }
        }
    }

    private void createNewShrinkerTransform(VariantScope scope, TaskFactory taskFactory) {
        NewShrinkerTransform transform = new NewShrinkerTransform(scope);
        addProguardConfigFiles(transform, scope.getVariantData());

        if (scope.getVariantConfiguration().isTestCoverageEnabled()) {
            addJacocoShrinkerFlags(transform);
        }

        if (getIncrementalMode(scope.getVariantConfiguration()) != IncrementalMode.NONE) {
            //TODO: This is currently overly broad, as finding the actual application class
            //      requires manually parsing the manifest (See CreateManifestKeepList)
            transform.keep("class ** extends android.app.Application {*;}");
            transform.keep("class com.android.tools.fd.** {*;}");
        }

        scope.getTransformManager().addTransform(taskFactory, scope, transform);
    }

    private void createProguardTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull VariantScope variantScope,
            @Nullable Configuration mappingConfiguration,
            boolean createJarFile) {
        final BaseVariantData<? extends BaseVariantOutputData> variantData = variantScope
                .getVariantData();
        final GradleVariantConfiguration variantConfig = variantData.getVariantConfiguration();
        final BaseVariantData testedVariantData = variantScope.getTestedVariantData();

        ProGuardTransform transform = new ProGuardTransform(variantScope, createJarFile);

        if (testedVariantData != null) {
            // Don't remove any code in tested app.
            transform.dontshrink();
            transform.dontoptimize();

            // We can't call dontobfuscate, since that would make ProGuard ignore the mapping file.
            transform.keep("class * {*;}");
            transform.keep("interface * {*;}");
            transform.keep("enum * {*;}");
            transform.keepattributes();

            // All -dontwarn rules for test dependencies should go in here:
            transform.setConfigurationFiles(
                    Suppliers.ofInstance(
                            (Collection<File>)testedVariantData.getVariantConfiguration().getTestProguardFiles()));

            // register the mapping file which may or may not exists (only exist if obfuscation)
            // is enabled.
            transform.applyTestedMapping(testedVariantData.getMappingFile());

        } else {
            if (variantConfig.isTestCoverageEnabled()) {
                addJacocoShrinkerFlags(transform);
            }

            addProguardConfigFiles(transform, variantData);

            if (mappingConfiguration != null) {
                transform.applyTestedMapping(mappingConfiguration);
            }
        }

        AndroidTask<?> task = variantScope.getTransformManager().addTransform(taskFactory,
                variantScope, transform, new TransformTask.ConfigActionCallback<ProGuardTransform>() {
                    @Override
                    public void callback(
                            @NonNull final ProGuardTransform transform,
                            @NonNull final TransformTask task) {
                        variantData.mappingFileProviderTask = new FileSupplier() {
                            @NonNull
                            @Override
                            public Task getTask() {
                                return task;
                            }

                            @Override
                            public File get() {
                                return transform.getMappingFile();
                            }
                        };
                    }
                });

        if (mappingConfiguration != null) {
            verifyNotNull(task);
            task.dependsOn(taskFactory, mappingConfiguration);
        }
    }

    private void createShrinkResourcesTransform(
            @NonNull TaskFactory taskFactory,
            @NonNull VariantScope scope) {
        if (!scope.getVariantConfiguration().getBuildType().isShrinkResources()) {
            return;
        }

        if (getIncrementalMode(scope.getVariantConfiguration()) != IncrementalMode.NONE) {
            logger.warn("Instant Run: Resource shrinker automatically disabled for {}",
                    scope.getVariantConfiguration().getFullName());
            // Disabled for instant run, as shrinking is automatically disabled.
            return;
        }

        if (!scope.getVariantConfiguration().getBuildType().isUseProguard()) {
            throw new IllegalArgumentException(
                    "Build-in class shrinker and resource shrinking are not supported yet.");
        }

        // if resources are shrink, insert a no-op transform per variant output
        // to transform the res package into a stripped res package
        for (final BaseVariantOutputData variantOutputData : scope.getVariantData().getOutputs()) {
            VariantOutputScope variantOutputScope = variantOutputData.getScope();

            ShrinkResourcesTransform shrinkResTransform = new ShrinkResourcesTransform(
                    variantOutputData,
                    variantOutputScope.getProcessResourcePackageOutputFile(),
                    variantOutputScope.getCompressedResourceFile(),
                    androidBuilder,
                    logger);
            AndroidTask<TransformTask> shrinkTask = scope.getTransformManager()
                    .addTransform(taskFactory, variantOutputScope, shrinkResTransform);
            // need to record this task since the package task will not depend
            // on it through the transform manager.
            variantOutputScope.setShrinkResourcesTask(shrinkTask);
        }
    }

    private static void addJacocoShrinkerFlags(ProguardConfigurable transform) {
        // when collecting coverage, don't remove the JaCoCo runtime
        transform.keep("class com.vladium.** {*;}");
        transform.keep("class org.jacoco.** {*;}");
        transform.keep("interface org.jacoco.** {*;}");
        transform.dontwarn("org.jacoco.**");
    }

    private void addProguardConfigFiles(
            ProguardConfigurable transform,
            final BaseVariantData<? extends BaseVariantOutputData> variantData) {
        final GradleVariantConfiguration variantConfig = variantData.getVariantConfiguration();
        transform.setConfigurationFiles(new Supplier<Collection<File>>() {
            @Override
            public Collection<File> get() {
                Set<File> proguardFiles = variantConfig.getProguardFiles(
                        true,
                        Collections.singletonList(getDefaultProguardFile(
                                TaskManager.DEFAULT_PROGUARD_CONFIG_FILE)));

                // use the first output when looking for the proguard rule output of
                // the aapt task. The different outputs are not different in a way that
                // makes this rule file different per output.
                BaseVariantOutputData outputData = variantData.getOutputs().get(0);
                proguardFiles.add(outputData.processResourcesTask.getProguardOutputFile());
                return proguardFiles;
            }
        });
    }

    public void createReportTasks(
            List<BaseVariantData<? extends BaseVariantOutputData>> variantDataList) {
        DependencyReportTask dependencyReportTask =
                project.getTasks().create("androidDependencies", DependencyReportTask.class);
        dependencyReportTask.setDescription("Displays the Android dependencies of the project.");
        dependencyReportTask.setVariants(variantDataList);
        dependencyReportTask.setGroup(ANDROID_GROUP);

        SigningReportTask signingReportTask =
                project.getTasks().create("signingReport", SigningReportTask.class);
        signingReportTask.setDescription("Displays the signing info for each variant.");
        signingReportTask.setVariants(variantDataList);
        signingReportTask.setGroup(ANDROID_GROUP);
    }

    public void createAnchorTasks(@NonNull TaskFactory tasks, @NonNull VariantScope scope) {
        createPreBuildTasks(tasks, scope);

        // also create sourceGenTask
        final BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        scope.setSourceGenTask(androidTasks.create(tasks,
                scope.getTaskName("generate", "Sources"),
                Task.class,
                new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        variantData.sourceGenTask = task;
                    }
                }));
        // and resGenTask
        scope.setResourceGenTask(androidTasks.create(tasks,
                scope.getTaskName("generate", "Resources"),
                Task.class,
                new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        variantData.resourceGenTask = task;
                    }

                }));

        scope.setAssetGenTask(androidTasks.create(tasks,
                scope.getTaskName("generate", "Assets"),
                Task.class,
                new Action<Task>() {
                    @Override
                    public void execute(Task task) {
                        variantData.assetGenTask = task;
                    }
                }));

        if (!variantData.getType().isForTesting()
                && variantData.getVariantConfiguration().getBuildType().isTestCoverageEnabled()) {
            scope.setCoverageReportTask(androidTasks.create(tasks,
                    scope.getTaskName("create", "CoverageReport"),
                    Task.class,
                    new Action<Task>() {
                        @Override
                        public void execute(Task task) {
                            task.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
                            task.setDescription(String.format(
                                    "Creates test coverage reports for the %s variant.",
                                    variantData.getName()));
                        }
                    }));
        }

        // and compile task
        createCompileAnchorTask(tasks, scope);
    }

    private void createPreBuildTasks(@NonNull TaskFactory tasks, @NonNull VariantScope scope) {
        final BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        variantData.preBuildTask = project.getTasks().create(scope.getTaskName("pre", "Build"));
        variantData.preBuildTask.dependsOn(MAIN_PREBUILD);

        PrepareDependenciesTask prepareDependenciesTask = project.getTasks().create(
        scope.getTaskName("prepare", "Dependencies"), PrepareDependenciesTask.class);

        variantData.prepareDependenciesTask = prepareDependenciesTask;
        prepareDependenciesTask.dependsOn(variantData.preBuildTask);

        prepareDependenciesTask.setAndroidBuilder(androidBuilder);
        prepareDependenciesTask.setVariantName(scope.getVariantConfiguration().getFullName());
        prepareDependenciesTask.setVariant(variantData);

        // for all libraries required by the configurations of this variant, make this task
        // depend on all the tasks preparing these libraries.
        VariantDependencies configurationDependencies = variantData.getVariantDependency();
        prepareDependenciesTask.addChecker(configurationDependencies.getChecker());

        for (LibraryDependencyImpl lib : configurationDependencies.getLibraries()) {
            dependencyManager.addDependencyToPrepareTask(variantData, prepareDependenciesTask, lib);
        }
    }

    private void createCompileAnchorTask(@NonNull TaskFactory tasks, @NonNull final VariantScope scope) {
        final BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        scope.setCompileTask(androidTasks.create(tasks, new TaskConfigAction<Task>() {
            @NonNull
            @Override
            public String getName() {
                return scope.getTaskName("compile", "Sources");
            }

            @NonNull
            @Override
            public Class<Task> getType() {
                return Task.class;
            }

            @Override
            public void execute(@NonNull Task task) {
                variantData.compileTask = task;
                variantData.compileTask.setGroup(BUILD_GROUP);
            }
        }));
        variantData.assembleVariantTask.dependsOn(scope.getCompileTask().getName());
    }

    public void createCheckManifestTask(@NonNull TaskFactory tasks, @NonNull VariantScope scope) {
        final BaseVariantData<? extends BaseVariantOutputData> variantData = scope.getVariantData();
        final String name = variantData.getVariantConfiguration().getFullName();
        scope.setCheckManifestTask(androidTasks.create(tasks,
                scope.getTaskName("check", "Manifest"),
                CheckManifest.class,
                new Action<CheckManifest>() {
                    @Override
                    public void execute(CheckManifest checkManifestTask) {
                        variantData.checkManifestTask = checkManifestTask;
                        checkManifestTask.setVariantName(name);
                        ConventionMappingHelper.map(checkManifestTask, "manifest",
                                new Callable<File>() {
                                    @Override
                                    public File call() throws Exception {
                                        return variantData.getVariantConfiguration()
                                                .getDefaultSourceSet().getManifestFile();
                                    }
                                });
                    }

                }));
        scope.getCheckManifestTask().dependsOn(tasks, variantData.preBuildTask);
        variantData.prepareDependenciesTask.dependsOn(scope.getCheckManifestTask().getName());
    }

    public static void optionalDependsOn(@NonNull Task main, Task... dependencies) {
        for (Task dependency : dependencies) {
            if (dependency != null) {
                main.dependsOn(dependency);
            }

        }

    }

    public static void optionalDependsOn(@NonNull Task main, @NonNull List<?> dependencies) {
        for (Object dependency : dependencies) {
            if (dependency != null) {
                main.dependsOn(dependency);
            }

        }

    }

    @NonNull
    private static List<ManifestDependencyImpl> getManifestDependencies(
            List<LibraryDependency> libraries) {

        List<ManifestDependencyImpl> list = Lists.newArrayListWithCapacity(libraries.size());

        for (LibraryDependency lib : libraries) {
            // get the dependencies
            List<ManifestDependencyImpl> children = getManifestDependencies(lib.getDependencies());
            list.add(new ManifestDependencyImpl(lib.getName(), lib.getManifest(), children));
        }

        return list;
    }

    @NonNull
    protected Logger getLogger() {
        return logger;
    }

    @NonNull
    public AndroidTaskRegistry getAndroidTasks() {
        return androidTasks;
    }

    private File getDefaultProguardFile(String name) {
        File sdkDir = sdkHandler.getAndCheckSdkFolder();
        return new File(sdkDir,
                SdkConstants.FD_TOOLS + File.separatorChar + SdkConstants.FD_PROGUARD
                        + File.separatorChar + name);
    }

    public void addDataBindingDependenciesIfNecessary(DataBindingOptions options) {
        if (!options.isEnabled()) {
            return;
        }
        String version = Objects.firstNonNull(options.getVersion(),
                dataBindingBuilder.getCompilerVersion());
        project.getDependencies().add("compile", SdkConstants.DATA_BINDING_LIB_ARTIFACT + ":"
                + dataBindingBuilder.getLibraryVersion(version));
        project.getDependencies().add("compile", SdkConstants.DATA_BINDING_BASELIB_ARTIFACT + ":"
                + dataBindingBuilder.getBaseLibraryVersion(version));
        project.getDependencies().add("provided",
                SdkConstants.DATA_BINDING_ANNOTATION_PROCESSOR_ARTIFACT + ":" +
                version);
        if (options.getAddDefaultAdapters()) {
            project.getDependencies()
                    .add("compile", SdkConstants.DATA_BINDING_ADAPTER_LIB_ARTIFACT + ":" +
                    dataBindingBuilder.getBaseAdaptersVersion(version));
        }
    }
}
