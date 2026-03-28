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

package com.sky.xposed.rimet.ui.base;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDialogFragment;

import com.sky.xposed.rimet.Constant;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.ui.base.BaseDialogFragment with
 * androidx {@link AppCompatDialogFragment}. The ResourceManager/XResourceManager
 * helpers from the source are omitted — they are only available inside the hooked
 * app's process, not in the module's own UI process.
 */
public abstract class BaseDialog extends AppCompatDialogFragment {

    /**
     * Returns the SharedPreferences file used by both the module UI and the hook code.
     * Settings written here are read by {@code ConfigManager} in DingTalk's process.
     */
    protected SharedPreferences getDefaultSharedPreferences() {
        return requireContext().getSharedPreferences(Constant.Name.RIMET, Context.MODE_PRIVATE);
    }
}
