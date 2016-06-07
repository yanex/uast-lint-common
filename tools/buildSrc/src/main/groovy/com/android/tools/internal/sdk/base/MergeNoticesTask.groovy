/*
 * Copyright (C) 2013 The Android Open Source Project
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
import com.android.tools.internal.BaseTask
import com.google.common.base.Charsets
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.google.common.io.Files
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskAction

class MergeNoticesTask extends BaseTask {

    File noticeFile
    String[] noticeTaskNames

    @TaskAction
    public void createNotice() {

        Set<File> noticeDirectories = Lists.newArrayList()

        for (String name : noticeTaskNames) {
            for (Project subproject : project.subprojects) {

                NamedDomainObjectSet<Task> matches = subproject.tasks.matching { t ->
                    name.equals(t.name)
                }
                matches.each { t ->
                    noticeDirectories.add(t.getNoticeDir())
                }
            }
        }

        // gather all the notice files from all the folder, de-duping the notice files from
        // the java deps.
        Set<String> noticeCache = Sets.newTreeSet();
        List<File> notices = Lists.newArrayList();
        for (File folder : noticeDirectories) {
            if (folder.isDirectory()) {
                gatherNoticesFromFolder(folder, noticeCache, notices)
            }
        }

        // merge and write the result
        BufferedWriter writer = Files.newWriter(getNoticeFile(), Charsets.UTF_8)
        mergeNotices(notices, writer);
        writer.close()
    }

     private static void gatherNoticesFromFolder(
            File folder,
            Set<String> filenameCache,
            List<File> noticeList) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    if (file.name.startsWith("NOTICE.txt_")) {
                        noticeList.add(file)
                    } else if (file.name.startsWith("NOTICE_")) {
                        if (!filenameCache.contains(file.name)) {
                            filenameCache.add(file.name)
                            noticeList.add(file)
                        }
                    }
                }
            }
        }
    }

    private static void mergeNotices(List<File> notices, BufferedWriter noticeWriter) {
        for (File file : notices) {
            List<String> lines = Files.readLines(file, Charsets.UTF_8)
            for (String line : lines) {
                noticeWriter.write(line, 0, line.length())
                noticeWriter.newLine()
            }
            noticeWriter.newLine()
        }
    }
}
