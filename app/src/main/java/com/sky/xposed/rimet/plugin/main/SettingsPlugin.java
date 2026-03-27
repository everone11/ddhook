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

package com.sky.xposed.rimet.plugin.main;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.data.model.PluginInfo;
import com.sky.xposed.rimet.plugin.base.BasePlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

/**
 * Created by sky on 2018/12/30.
 *
 * Adapted for libxposed API:
 *  - findMethod().after now uses XposedInterface.Chain instead of XC_MethodHook.MethodHookParam.
 *  - SimpleItemView (from xposed-common) replaced with a plain TextView entry.
 *  - ResourceUtil.getId() replaced with Context.getResources().getIdentifier().
 */
public class SettingsPlugin extends BasePlugin {

    private static final String TAG = "SettingsPlugin";

    public SettingsPlugin(XPluginManager pluginManager) {
        super(pluginManager);
    }

    @Override
    public Info getInfo() {
        return new PluginInfo(Constant.Plugin.MAIN_SETTINGS, "设置");
    }

    @Override
    public void onHandleLoadPackage() {

        findMethod(
                M.classz.class_android_user_settings_activity_NewSettingActivity,
                M.method.method_android_user_settings_activity_NewSettingActivity_onCreate,
                Bundle.class)
                .after(chain -> {

                    final Activity activity = (Activity) chain.getThisObject();

                    // Find the anchor view by resource name; the resource belongs to DingTalk app.
                    int anchorId = activity.getResources().getIdentifier(
                            getXString(M.res.res_setting_msg_notice), "id",
                            activity.getPackageName());

                    if (anchorId == 0) {
                        Log.w(TAG, "Anchor view not found in settings");
                        return;
                    }

                    View anchorView = activity.findViewById(anchorId);
                    if (anchorView == null) return;

                    ViewGroup viewGroup = (ViewGroup) anchorView.getParent();
                    if (viewGroup == null) return;

                    final int index = viewGroup.indexOfChild(anchorView);

                    // Create a simple entry item (replaces SimpleItemView from xposed-common)
                    TextView entryView = new TextView(activity);
                    entryView.setPadding(32, 24, 32, 24);
                    entryView.setTextSize(17);
                    entryView.setText(Constant.Name.TITLE);
                    entryView.setOnClickListener(v -> openSettings(activity));

                    viewGroup.addView(entryView, index);
                });
    }

    @Override
    public void openSettings(Activity activity) {

        XPluginManager pluginManager = getPluginManager();
        if (pluginManager.getXPluginById(Constant.Plugin.DING_DING) != null) {
            pluginManager.getXPluginById(Constant.Plugin.DING_DING).openSettings(activity);
        }
    }
}
