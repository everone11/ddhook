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

package com.sky.xposed.rimet.ui.dialog;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.widget.SwitchCompat;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.ui.view.SwitchItemView with standard
 * {@link SwitchCompat} (via {@link CommonDialog#addSwitch}).
 */
public class DevelopDialog extends CommonDialog {

    private SwitchCompat mSwDevelopEnable;

    @Override
    public void createView(LinearLayout container) {
        mSwDevelopEnable = addSwitch(container, "微信调试", null);
    }

    @Override
    protected void initView(View view, Bundle args) {
        super.initView(view, args);
        setTitle("调试设置");
        // No persistent key defined for the develop flag in Constant.XFlag;
        // bind to a custom prefs key so it at least persists between sessions.
        mSwDevelopEnable.setChecked(
                getDefaultSharedPreferences().getBoolean("develop_enable", false));
        mSwDevelopEnable.setOnCheckedChangeListener((btn, checked) ->
                getDefaultSharedPreferences()
                        .edit().putBoolean("develop_enable", checked).apply());
    }
}
