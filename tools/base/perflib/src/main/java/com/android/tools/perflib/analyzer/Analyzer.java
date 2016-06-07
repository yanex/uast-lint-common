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
package com.android.tools.perflib.analyzer;

import com.android.annotations.NonNull;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

public abstract class Analyzer {

    public abstract boolean accept(@NonNull CaptureGroup captureGroup);

    @NonNull
    public abstract AnalysisReport analyze(@NonNull CaptureGroup captureGroup,
            @NonNull Set<AnalysisReport.Listener> listeners,
            @NonNull Set<? extends AnalyzerTask> tasks,
            @NonNull Executor taskCompleteExecutor,
            @NonNull ExecutorService taskExecutor);

    public abstract void cancel();
}
