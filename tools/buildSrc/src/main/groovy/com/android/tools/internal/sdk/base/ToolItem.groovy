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

package com.android.tools.internal.sdk.base

import com.google.common.collect.Lists
import org.gradle.api.Project

/**
 * A file or folder going in the tools folder of the SDK.
 * Contains an object representing the file/folder to copy. This will resolved later.
 * Also contains destination information and other properties
 */
class ToolItem {

    /** From information: can be a closure returning a string/file, or a string/file directly */
    private final Object itemPath

    private List<Object> builtByTasks

    /** notice can be a string or a file */
    private Object notice = 'NOTICE'

    private String destinationPath
    private String name
    private boolean flatten = false
    private boolean executable = false

    private String sourcePath

    ToolItem(Object itemPath) {
        this.itemPath = itemPath
    }

    void into(String destinationPath) {
        this.destinationPath = destinationPath
    }

    void name(String name) {
        this.name = name
    }

    void notice(Object notice) {
        this.notice = notice
    }

    void executable(boolean b) {
        this.executable = b
    }

    void flatten(boolean b) {
        this.flatten = b
    }

    void builtBy(Object... tasks) {
        if (builtByTasks == null) {
            builtByTasks = Lists.newArrayListWithExpectedSize(tasks.length)
        }

        Collections.addAll(builtByTasks, tasks)
    }

    File getSourceFile(Project project) {
        Object from = itemPath

        File sourceFile = null

        if (from instanceof Closure) {
            from = ((Closure) from).call()
        }

        if (from instanceof GString) {
            from = from.toString()
        }

        if (from instanceof File) {
            sourceFile = (File) from
            sourcePath = sourceFile.path

        } else if (from instanceof String) {
            sourcePath = (String) from
            sourceFile = project.file(sourcePath)
        }

        if (sourceFile == null) {
            throw new RuntimeException("Unable to find source file for path '${sourcePath}' from ${this}")
        }

        return sourceFile
    }

    String getSourcePath() {
        return sourcePath
    }

    String getDestinationPath() {
        return destinationPath
    }

    String getName() {
        return name
    }

    Object getNotice() {
        return notice
    }

    boolean getFlatten() {
        return flatten
    }

    boolean getExecutable() {
        return executable
    }

    List<Object> getBuiltByTasks() {
        if (builtByTasks == null) {
            return Collections.emptyList()
        }
        return builtByTasks
    }

    @Override
    public String toString() {
        return "ToolItem{" +
                "itemPath=" + itemPath +
                ", builtByTasks=" + builtByTasks +
                ", destinationPath='" + destinationPath + '\'' +
                ", name='" + name + '\'' +
                ", flatten=" + flatten +
                ", executable=" + executable +
                '}';
    }
}
