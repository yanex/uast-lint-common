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
import com.google.common.base.Charsets
import com.google.common.base.Joiner
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import com.google.common.collect.Lists
import com.google.common.io.Files
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
/**
 * Task to copy tools items and their notice file.
 */
class CopyToolItemsTask extends DefaultTask {

    List<ToolItem> items;

    File itemOutputDir

    File noticeDir

    @TaskAction
    void copy() {
        File outDir = getItemOutputDir()

        Project p = getProject()

        ListMultimap<File, String> noticeToFilesMap = ArrayListMultimap.create()

        if (items != null) {
            for (ToolItem item : items) {
                File sourceFile = item.getSourceFile(p)

                Object noticePath = item.getNotice()
                File noticeFile = null
                if (noticePath != null) {
                    noticeFile = project.file(noticePath)
                    if (noticeFile == null) {
                        throw new RuntimeException("No notice file specified for item '${item.getSourcePath()}'")
                    } else if (!noticeFile.isFile()) {
                        throw new RuntimeException("Missing notice for item '${item.getSourcePath()}': ${noticeFile}")
                    }
                }

                File toFolder = outDir
                String destinationPath = item.getDestinationPath()
                if (destinationPath != null) {
                    toFolder = new File(outDir, destinationPath)//.replace('/', File.separatorChar))
                    toFolder.mkdirs()
                }

                if (sourceFile.isFile()) {
                    File toFile = copyFile(sourceFile, toFolder, item)
                    if (item.getExecutable()) {
                        toFile.setExecutable(true)
                    }

                    if (noticeFile != null) {
                        linkNoticeToFiles(noticeToFilesMap, noticeFile, outDir, Collections.singletonList(toFile))
                    }

                } else if (sourceFile.isDirectory()) {
                    List<File> toFiles = copyFolderItems(sourceFile, toFolder, item.getFlatten())

                    if (noticeFile != null) {
                        linkNoticeToFiles(noticeToFilesMap, noticeFile, outDir, toFiles)
                    }
                } else {
                    throw new RuntimeException("Missing sdk-files: ${sourceFile}")
                }
            }
        }

        outDir = getNoticeDir()
        outDir.deleteDir()
        outDir.mkdirs()

        int i = 0;
        for (File noticeFile : noticeToFilesMap.keySet()) {
            copyNoticeAndAddHeader(noticeFile, new File(outDir, "NOTICE.txt_${i}"), noticeToFilesMap.get(noticeFile))
            i++
        }
    }

    private static void linkNoticeToFiles(ListMultimap<File, String> noticeToFiles,
                                          File noticeFile, File rootFolder, List<File> files) {

        int length = rootFolder.getPath().length() + 1;
        for (File file : files) {
            String path = file.getPath().substring(length)
            noticeToFiles.put(noticeFile, path)
        }
    }

    protected File copyFile(File fromFile, File toFolder, ToolItem item) {
        File toFile = new File(toFolder, (item != null && item.getName() != null) ? item.getName() : fromFile.name)

        String fromPath = item != null ? item.getSourcePath() : null
        if (fromPath != null) {
            logger.info("$fromPath -> $toFile")
        } else {
            logger.info("$fromFile -> $toFile")
        }
        Files.copy(fromFile, toFile)

        if (fromFile.canExecute()) {
            toFile.setExecutable(true)
        }

        return toFile
    }

    private List<File> copyFolderItems(File folder, File destFolder, boolean flatten) {
        List<File> copiedFiles = Lists.newArrayList()

        File[] files = folder.listFiles()
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    copiedFiles.add(copyFile(file, destFolder, null))
                } else if (file.isDirectory()) {
                    File newToFolder = destFolder
                    if (!flatten) {
                        newToFolder = new File(destFolder, file.name)
                        newToFolder.mkdirs()
                    }

                    copiedFiles.addAll(copyFolderItems(file, newToFolder, flatten))
                }
            }
        }

        return copiedFiles
    }

    private static void copyNoticeAndAddHeader(File from, File to, List<String> names) {
        List<String> lines = Files.readLines(from, Charsets.UTF_8)
        List<String> noticeLines = Lists.newArrayListWithCapacity(lines.size() + 4)
        noticeLines.addAll([
                "============================================================",
                "Notices for file(s):"])
        noticeLines.addAll(names)
        noticeLines.add(
                "------------------------------------------------------------")
        noticeLines.addAll(lines)

        Files.write(Joiner.on("\n").join(noticeLines.iterator()), to, Charsets.UTF_8)
    }
}
