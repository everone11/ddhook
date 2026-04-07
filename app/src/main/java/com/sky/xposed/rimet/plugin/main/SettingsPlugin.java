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
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sky.xposed.rimet.BuildConfig;
import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.data.model.PluginInfo;
import com.sky.xposed.rimet.plugin.base.BasePlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

/**
 * Created by sky on 2018/12/30.
 *
 * Adapted for libxposed API:
 *  - Hooks both NewSettingActivity and UserSettingsActivity (matching anysoft/xposed-rimet).
 *  - findMethod().after now uses XposedInterface.Chain instead of XC_MethodHook.MethodHookParam.
 *  - SimpleItemView (from xposed-common) replaced with a LinearLayout item row showing the
 *    module title and version, followed by a separator line.
 *  - ResourceUtil.getId() replaced with Context.getResources().getIdentifier().
 */
public class SettingsPlugin extends BasePlugin {

    private static final String TAG = "SettingsPlugin";

    private static final int COLOR_DIVIDER = 0xFFEFEFEF;
    private static final int ITEM_PADDING_DP = 16;
    private static final int TEXT_SIZE_TITLE_SP = 16;
    private static final int TEXT_SIZE_VERSION_SP = 14;
    private static final int DIVIDER_HEIGHT_DP = 1;

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
                .after(chain -> onHandleSettings((Activity) chain.getThisObject()));

        findMethod(
                M.classz.class_android_user_settings_activity_UserSettingsActivity,
                M.method.method_android_user_settings_activity_UserSettingsActivity_onCreate,
                Bundle.class)
                .after(chain -> onHandleSettings((Activity) chain.getThisObject()));
    }

    private void onHandleSettings(Activity activity) {

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

        // Container LinearLayout holds the entry item and a separator line.
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        // Item row: name on the left, version tag on the right.
        LinearLayout itemRow = new LinearLayout(activity);
        itemRow.setOrientation(LinearLayout.HORIZONTAL);
        itemRow.setGravity(Gravity.CENTER_VERTICAL);
        int paddingH = dp2px(activity, ITEM_PADDING_DP);
        int paddingV = dp2px(activity, ITEM_PADDING_DP);
        itemRow.setPadding(paddingH, paddingV, paddingH, paddingV);
        itemRow.setClickable(true);
        itemRow.setFocusable(true);

        TextView nameView = new TextView(activity);
        nameView.setText(Constant.Name.TITLE);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_TITLE_SP);
        nameView.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        itemRow.addView(nameView, nameParams);

        TextView versionView = new TextView(activity);
        versionView.setText("v" + BuildConfig.VERSION_NAME);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_VERSION_SP);
        versionView.setTextColor(Color.GRAY);
        itemRow.addView(versionView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        itemRow.setOnClickListener(v -> openSettings(activity));
        container.addView(itemRow,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        // Separator line.
        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_DIVIDER);
        container.addView(divider,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, DIVIDER_HEIGHT_DP)));

        viewGroup.addView(container, index);
    }

    private static int dp2px(Activity activity, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                activity.getResources().getDisplayMetrics()));
    }

    @Override
    public void openSettings(Activity activity) {

        XPluginManager pluginManager = getPluginManager();
        if (pluginManager.getXPluginById(Constant.Plugin.DING_DING) != null) {
            pluginManager.getXPluginById(Constant.Plugin.DING_DING).openSettings(activity);
        }
    }
}
