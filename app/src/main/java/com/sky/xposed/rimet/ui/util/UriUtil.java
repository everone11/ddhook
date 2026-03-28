/*
 * Copyright (c) 2019 The sky Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sky.xposed.rimet.ui.util;

import android.net.Uri;

import com.sky.xposed.rimet.BuildConfig;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.util.ResourceUtil.resourceIdToUri with
 * a direct android.resource URI construction (no external library needed).
 */
public class UriUtil {

    private UriUtil() {
    }

    /**
     * Converts a drawable/resource ID into an {@code android.resource://} URI that
     * can be consumed by image loaders or {@link android.widget.ImageView#setImageURI}.
     */
    public static Uri getResource(int resId) {
        return Uri.parse("android.resource://" + BuildConfig.APPLICATION_ID + "/" + resId);
    }
}
