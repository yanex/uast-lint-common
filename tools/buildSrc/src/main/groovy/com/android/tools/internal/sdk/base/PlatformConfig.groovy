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
import org.gradle.api.Action

/**
 * A Config to build the SDK tools for a given platform.
 *
 * This is mostly a list of files to copy.
 */
class PlatformConfig {

    private final String name
    private final List<ToolItem> items = Lists.newArrayList()

    PlatformConfig(String name) {
        this.name = name
    }

    String getName() {
        return name
    }

    void item(Object fromPath) {
        getToolItem(fromPath)
    }

    void item(Object fromPath, Action<ToolItem> config) {
        ToolItem item = getToolItem(fromPath)
        config.execute(item)
    }

    private getToolItem(Object fromPath) {
        ToolItem item = new ToolItem(fromPath)
        items.add(item)
        return item
    }

    List<ToolItem> getItems() {
        return items
    }

    List<Object> getBuiltBy() {
        List<Object> objects = Lists.newArrayListWithExpectedSize(items.size())
        for (ToolItem item : items) {
            objects.addAll(item.getBuiltByTasks())
        }

        return objects
    }
}
