package com.android.build.gradle.tasks;

import static com.android.sdklib.BuildToolInfo.PathId.ZIP_ALIGN;

import com.android.annotations.NonNull;
import com.android.build.gradle.AndroidGradleOptions;
import com.android.build.gradle.internal.annotations.ApkFile;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantOutputScope;
import com.android.build.gradle.internal.tasks.FileSupplier;
import com.android.build.gradle.internal.variant.ApkVariantOutputData;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.ParallelizableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecSpec;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

@ParallelizableTask
public class ZipAlign extends DefaultTask implements FileSupplier {

    private boolean useOldPackaging;

    // ----- PUBLIC TASK API -----

    @OutputFile
    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    @InputFile
    public File getInputFile() {
        return inputFile;
    }

    public void setInputFile(File inputFile) {
        this.inputFile = inputFile;
    }

    // ----- PRIVATE TASK API -----

    private File outputFile;
    @ApkFile
    private File inputFile;
    @ApkFile
    private File zipAlignExe;

    private InstantRunBuildContext instantRunBuildContext;

    public void setInstantRunBuildContext(InstantRunBuildContext instantRunBuildContext) {
        this.instantRunBuildContext = instantRunBuildContext;
    }

    @InputFile
    public File getZipAlignExe() {
        return zipAlignExe;
    }

    public void setZipAlignExe(File zipAlignExe) {
        this.zipAlignExe = zipAlignExe;
    }

    @TaskAction
    public void zipAlign() {
        getProject().exec(new Action<ExecSpec>() {
            @Override
            public void execute(ExecSpec execSpec) {
                execSpec.executable(getZipAlignExe());
                execSpec.args("-f", "4");
                execSpec.args(getInputFile());
                execSpec.args(getOutputFile());
            }
        });
        // mark this APK production, this will eventually be saved when instant-run is enabled.
        try {
            instantRunBuildContext.addChangedFile(InstantRunBuildContext.FileType.MAIN,
                    getOutputFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ----- FileSupplierTask -----

    @Override
    public File get() {
        return getOutputFile();
    }

    @NonNull
    @Override
    public Task getTask() {
        return this;
    }

    // ----- ConfigAction -----

    public static class ConfigAction implements TaskConfigAction<ZipAlign> {

        private final VariantOutputScope scope;

        @Override
        public String getName() {
            return scope.getTaskName("zipalign");
        }

        @Override
        public Class<ZipAlign> getType() {
            return ZipAlign.class;
        }

        public ConfigAction(VariantOutputScope scope) {
            this.scope = scope;
        }

        @Override
        public void execute(ZipAlign zipAlign) {
            ((ApkVariantOutputData) scope.getVariantOutputData()).zipAlignTask = zipAlign;
            ConventionMappingHelper.map(zipAlign, "inputFile", new Callable<File>() {
                @Override
                public File call() throws Exception {
                    // wire to the output of the package task.
                    PackageApplication packageApplicationTask = ((ApkVariantOutputData) scope
                            .getVariantOutputData()).packageApplicationTask;
                    return packageApplicationTask == null
                            ? scope.getPackageApk()
                            : packageApplicationTask.getOutputFile();
                }
            });
            ConventionMappingHelper.map(zipAlign, "outputFile", new Callable<File>() {
                @Override
                public File call() throws Exception {
                    return scope.getGlobalScope().getProject().file(
                            scope.getGlobalScope().getApkLocation() + "/" +
                                    scope.getGlobalScope().getProjectBaseName() + "-" +
                                    scope.getVariantOutputData().getBaseName() + ".apk");
                }
            });
            ConventionMappingHelper.map(zipAlign, "zipAlignExe", new Callable<File>() {
                @Override
                public File call() throws Exception {
                    String path = scope.getGlobalScope().getAndroidBuilder().getTargetInfo()
                            .getBuildTools().getPath(ZIP_ALIGN);
                    if (path != null) {
                        return new File(path);
                    }
                    return null;
                }
            });
            zipAlign.instantRunBuildContext = scope.getVariantScope().getInstantRunBuildContext();
            zipAlign.useOldPackaging = AndroidGradleOptions.useOldPackaging(
                    scope.getGlobalScope().getProject());
        }
    }
}
