/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.build.gradle.internal.dsl;

import com.android.annotations.Nullable;

import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * DSL object for configuring dx options.
 */
public class DexOptions implements com.android.builder.core.DexOptions {

    private boolean isIncrementalFlag = false;

    private boolean isPreDexLibrariesFlag = true;

    private boolean isJumboModeFlag = false;

    private Boolean isDexInProcess = null;

    private Integer threadCount = null;

    private String javaMaxHeapSize;

    private Integer maxProcessCount = null;

    public void setIncremental(boolean isIncremental) {
        // TODO: Print out a warning, that this is ignored.
        isIncrementalFlag = isIncremental;
    }

    /**
     * Whether to enable the incremental mode for dx. This has many limitations and may not
     * work. Use carefully.
     */
    @Override
    @Input
    public boolean getIncremental() {
        return isIncrementalFlag;
    }

    public void setPreDexLibraries(boolean flag) {
        isPreDexLibrariesFlag = flag;
    }

    /**
     * Whether to pre-dex libraries. This can improve incremental builds, but clean builds may
     * be slower.
     */
    @Override
    @Input
    public boolean getPreDexLibraries() {
        return isPreDexLibrariesFlag;
    }

    public void setJumboMode(boolean flag) {
        isJumboModeFlag = flag;
    }

    /**
     * Enable jumbo mode in dx (--force-jumbo).
     */
    @Override
    @Input
    public boolean getJumboMode() {
        return isJumboModeFlag;
    }

    public void setJavaMaxHeapSize(String theJavaMaxHeapSize) {
        if (theJavaMaxHeapSize.matches("\\d+[kKmMgGtT]?")) {
            javaMaxHeapSize = theJavaMaxHeapSize;
        } else {
            throw new IllegalArgumentException(
                    "Invalid max heap size DexOption. See `man java` for valid -Xmx arguments.");
        }
    }

    /**
     * Sets the -JXmx* value when calling dx. Format should follow the 1024M pattern.
     */
    @Override
    @Optional @Input
    @Nullable
    public String getJavaMaxHeapSize() {
        return javaMaxHeapSize;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    /**
     * Number of threads to use when running dx. Defaults to 4.
     */
    @Override
    @Nullable
    public Integer getThreadCount() {
        return threadCount;
    }


    /**
     * Returns the maximum number of concurrent processes that can be used to dex. Defaults to 2.
     *
     * <p>Be aware that the number of concurrent process times the memory requirement represent the
     * minimum amount of memory that will be used by the dx processes:
     * {@code Total Memory = getMaxProcessCount() * getJavaMaxHeapSize()}
     *
     * To avoid trashing, keep these two settings appropriate for your configuration.
     * @return the max number of concurrent dx processes.
     */
    @Nullable
    @Override
    public Integer getMaxProcessCount() {
        return maxProcessCount;
    }

    public void setMaxProcessCount(int maxProcessCount) {
        this.maxProcessCount = maxProcessCount;
    }

}
