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

package com.sky.xposed.rimet.plugin.dingding;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.data.model.PluginInfo;
import com.sky.xposed.rimet.plugin.base.BasePlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

import java.util.List;

/**
 * Created by sky on 2019/3/14.
 *
 * Adapted for libxposed API:
 *  - findMethod().before/after now use XposedInterface.Chain instead of XC_MethodHook.MethodHookParam.
 *  - chain.getThisObject() replaces param.thisObject.
 *  - chain.getArg(0) replaces param.args[0].
 *  - return null (without calling chain.proceed()) replaces param.setResult(null).
 */
public class DingDingPlugin extends BasePlugin {

    private Handler mHandler;

    public DingDingPlugin(Build build) {
        super(build.mPluginManager);
        mHandler = build.mHandler;
    }

    @Override
    public void setEnable(int flag, boolean enable) {
        mHandler.setEnable(flag, enable);
    }

    @Override
    public Info getInfo() {
        return new PluginInfo(Constant.Plugin.DING_DING, Constant.Name.TITLE);
    }

    @Override
    public void onHandleLoadPackage() {

        // Bypass security guard (skip original call, return null)
        findMethod(
                M.classz.class_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl,
                M.method.method_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl_initSecurityGuard,
                Context.class)
                .before(chain -> null);

        // Hook conversation-change event to detect red packets
        findMethod(
                M.classz.class_defpackage_ConversationChangeMaid,
                M.method.method_defpackage_ConversationChangeMaid_onLatestMessageChanged,
                List.class)
                .after(chain -> {
                    mHandler.onHandlerMessage((List) chain.getArg(0));
                });

        // Hook festival red-packet pick activity
        findMethod(
                M.classz.class_android_dingtalk_redpackets_activities_FestivalRedPacketsPickActivity,
                M.method.method_android_dingtalk_redpackets_activities_FestivalRedPacketsPickActivity_initView)
                .after(chain -> {
                    mHandler.onHandlerFestivalRedPacketsPick((Activity) chain.getThisObject());
                });

        // Hook regular red-packet pick activity
        findMethod(
                M.classz.class_android_dingtalk_redpackets_activities_PickRedPacketsActivity,
                M.method.method_android_dingtalk_redpackets_activities_PickRedPacketsActivity_initView)
                .after(chain -> {
                    mHandler.onHandlerPickRedPackets((Activity) chain.getThisObject());
                });
    }

    @Override
    public void openSettings(Activity activity) {
        showLocationDialog(activity);
    }

    /**
     * Shows a location-spoof settings dialog inside DingTalk's own process.
     * Reads and writes to the activity's local SharedPreferences so that the
     * hooks in {@link com.sky.xposed.rimet.plugin.system.SystemHookPlugin}
     * (which prefer the app's own local prefs when available) pick up changes
     * immediately — no cross-process IPC required.
     */
    private static void showLocationDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs =
                activity.getSharedPreferences(Constant.Name.RIMET, Context.MODE_PRIVATE);
        int pad = dp(activity, 16);
        int padV = dp(activity, 8);

        // Root container
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, padV, pad, padV);
        scrollView.addView(layout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Enable virtual location ──────────────────────────────────────────
        CheckBox cbEnable = new CheckBox(activity);
        cbEnable.setText("启用虚拟定位");
        cbEnable.setChecked(prefs.getBoolean(
                key(Constant.XFlag.ENABLE_LOCATION), false));
        layout.addView(cbEnable, rowParams(padV));

        addSectionLabel(layout, activity, "GPS 坐标", pad);
        EditText etLat = addField(layout, activity, "纬度 (Latitude)",
                prefs.getString(key(Constant.XFlag.LATITUDE), ""),
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED, padV);
        EditText etLon = addField(layout, activity, "经度 (Longitude)",
                prefs.getString(key(Constant.XFlag.LONGITUDE), ""),
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED, padV);
        EditText etOffset = addField(layout, activity, "随机偏移距离 (米, 0=不偏移)",
                prefs.getString(key(Constant.XFlag.LOCATION_OFFSET), ""),
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL, padV);

        addSectionLabel(layout, activity, "WiFi 信息", pad);
        EditText etWifiSsid = addField(layout, activity, "WiFi SSID",
                prefs.getString(key(Constant.XFlag.WIFI_SSID), ""),
                InputType.TYPE_CLASS_TEXT, padV);
        EditText etWifiBssid = addField(layout, activity, "WiFi BSSID",
                prefs.getString(key(Constant.XFlag.WIFI_BSSID), ""),
                InputType.TYPE_CLASS_TEXT, padV);
        EditText etWifiMac = addField(layout, activity, "WiFi MAC",
                prefs.getString(key(Constant.XFlag.WIFI_MAC), ""),
                InputType.TYPE_CLASS_TEXT, padV);

        addSectionLabel(layout, activity, "基站信息", pad);
        EditText etCellId = addField(layout, activity, "Cell ID",
                prefs.getString(key(Constant.XFlag.CELL_ID), ""),
                InputType.TYPE_CLASS_NUMBER, padV);
        EditText etCellLac = addField(layout, activity, "LAC",
                prefs.getString(key(Constant.XFlag.CELL_LAC), ""),
                InputType.TYPE_CLASS_NUMBER, padV);
        EditText etCellMcc = addField(layout, activity, "MCC (移动国家码)",
                prefs.getString(key(Constant.XFlag.CELL_MCC), ""),
                InputType.TYPE_CLASS_NUMBER, padV);
        EditText etCellMnc = addField(layout, activity, "MNC (移动网络码)",
                prefs.getString(key(Constant.XFlag.CELL_MNC), ""),
                InputType.TYPE_CLASS_NUMBER, padV);

        new AlertDialog.Builder(activity)
                .setTitle(Constant.Name.TITLE + " — 虚拟定位")
                .setView(scrollView)
                .setPositiveButton("保存", (d, w) ->
                        prefs.edit()
                                .putBoolean(key(Constant.XFlag.ENABLE_LOCATION),
                                        cbEnable.isChecked())
                                .putString(key(Constant.XFlag.LATITUDE),
                                        etLat.getText().toString().trim())
                                .putString(key(Constant.XFlag.LONGITUDE),
                                        etLon.getText().toString().trim())
                                .putString(key(Constant.XFlag.LOCATION_OFFSET),
                                        etOffset.getText().toString().trim())
                                .putString(key(Constant.XFlag.WIFI_SSID),
                                        etWifiSsid.getText().toString().trim())
                                .putString(key(Constant.XFlag.WIFI_BSSID),
                                        etWifiBssid.getText().toString().trim())
                                .putString(key(Constant.XFlag.WIFI_MAC),
                                        etWifiMac.getText().toString().trim())
                                .putString(key(Constant.XFlag.CELL_ID),
                                        etCellId.getText().toString().trim())
                                .putString(key(Constant.XFlag.CELL_LAC),
                                        etCellLac.getText().toString().trim())
                                .putString(key(Constant.XFlag.CELL_MCC),
                                        etCellMcc.getText().toString().trim())
                                .putString(key(Constant.XFlag.CELL_MNC),
                                        etCellMnc.getText().toString().trim())
                                .apply())
                .setNegativeButton("取消", null)
                .show();
    }

    private static String key(int flag) {
        return Integer.toString(flag);
    }

    private static int dp(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams rowParams(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private static void addSectionLabel(LinearLayout container,
            Context ctx, String text, int pad) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(0xFF888888);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        container.addView(tv, lp);
    }

    private static EditText addField(LinearLayout container, Context ctx,
            String hint, String value, int inputType, int topMargin) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setText(value);
        et.setInputType(inputType);
        et.setSingleLine(true);
        container.addView(et, rowParams(topMargin));
        return et;
    }

    public interface Handler {

        void setEnable(int flag, boolean enable);

        void onHandlerMessage(List conversations);

        void onHandlerFestivalRedPacketsPick(Activity activity);

        void onHandlerPickRedPackets(Activity activity);
    }

    public static class Build {

        private XPluginManager mPluginManager;
        private Handler mHandler;

        public Build(XPluginManager pluginManager) {
            mPluginManager = pluginManager;
        }

        public Build setHandler(Handler handler) {
            mHandler = handler;
            return this;
        }

        public XPlugin build() {
            return new DingDingPlugin(this);
        }
    }
}
