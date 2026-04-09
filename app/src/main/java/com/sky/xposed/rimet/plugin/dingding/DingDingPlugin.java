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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.data.model.LocationPreset;
import com.sky.xposed.rimet.data.model.PluginInfo;
import com.sky.xposed.rimet.plugin.base.BasePlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
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

    public DingDingPlugin(Build build) {
        super(build.mPluginManager);
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
     *
     * <p>The dialog includes an "当前地址" (Current Address) section at the top
     * where users can save named presets and switch between them to auto-fill
     * all location fields at once.
     */
    private static void showLocationDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs =
                activity.getSharedPreferences(Constant.Name.RIMET, Context.MODE_PRIVATE);
        int pad  = dp(activity, 16);
        int padV = dp(activity, 8);

        // Root container
        ScrollView scrollView = new ScrollView(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, padV, pad, padV);
        scrollView.addView(layout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 当前地址 (Current Address) ────────────────────────────────────
        addSectionLabel(layout, activity, "当前地址", pad);

        List<LocationPreset> presets = loadPresets(prefs);
        List<String> names = new ArrayList<>();
        names.add("未配置");
        for (LocationPreset p : presets) {
            names.add(p.name);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // Use MODE_DIALOG to avoid duplicate arrow / flicker when inside a ScrollView
        Spinner spinner = new Spinner(activity, Spinner.MODE_DIALOG);
        spinner.setAdapter(adapter);
        layout.addView(spinner, rowParams(padV));

        // Restore previously selected preset
        String savedName = prefs.getString(key(Constant.XFlag.SELECTED_PRESET_NAME), "");
        if (!savedName.isEmpty()) {
            int savedIndex = names.indexOf(savedName);
            if (savedIndex > 0) spinner.setSelection(savedIndex);
        }

        // Buttons: Save preset / Delete preset (created without listeners for now)
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        int btnMargin = dp(activity, 4);

        Button btnSavePreset = new Button(activity);
        btnSavePreset.setText("保存新地址");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.rightMargin = btnMargin;
        btnRow.addView(btnSavePreset, btnLp);

        Button btnDeletePreset = new Button(activity);
        btnDeletePreset.setText("删除地址");
        btnRow.addView(btnDeletePreset, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        layout.addView(btnRow, rowParams(0));

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

        // ── Spinner listener: auto-fill fields when a preset is selected ────
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    // 未配置 — clear all fields
                    etLat.setText("");
                    etLon.setText("");
                    etOffset.setText("");
                    etWifiSsid.setText("");
                    etWifiBssid.setText("");
                    etWifiMac.setText("");
                    etCellId.setText("");
                    etCellLac.setText("");
                    etCellMcc.setText("");
                    etCellMnc.setText("");
                    return;
                }
                LocationPreset p = presets.get(position - 1);
                etLat.setText(p.latitude);
                etLon.setText(p.longitude);
                etOffset.setText(p.offset);
                etWifiSsid.setText(p.wifiSsid);
                etWifiBssid.setText(p.wifiBssid);
                etWifiMac.setText(p.wifiMac);
                etCellId.setText(p.cellId);
                etCellLac.setText(p.cellLac);
                etCellMcc.setText(p.cellMcc);
                etCellMnc.setText(p.cellMnc);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ── "保存新地址" button: persist current field values as a named preset ──
        btnSavePreset.setOnClickListener(v -> {
            EditText nameInput = new EditText(activity);
            nameInput.setHint("分类名称");
            nameInput.setSingleLine(true);
            new AlertDialog.Builder(activity)
                    .setTitle("保存地址分类")
                    .setView(nameInput)
                    .setPositiveButton("保存", (d2, w2) -> {
                        String presetName = nameInput.getText().toString().trim();
                        if (presetName.isEmpty()) return;
                        LocationPreset preset = buildPresetFromFields(
                                presetName, etLat, etLon, etOffset,
                                etWifiSsid, etWifiBssid, etWifiMac,
                                etCellId, etCellLac, etCellMcc, etCellMnc);
                        presets.add(preset);
                        savePresets(prefs, presets);
                        names.add(presetName);
                        adapter.notifyDataSetChanged();
                        spinner.setSelection(names.size() - 1);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // ── "删除地址" button: remove the currently selected preset ─────────
        btnDeletePreset.setOnClickListener(v -> {
            int pos = spinner.getSelectedItemPosition();
            if (pos == 0) return; // placeholder — nothing to delete
            presets.remove(pos - 1);
            savePresets(prefs, presets);
            names.remove(pos);
            adapter.notifyDataSetChanged();
            spinner.setSelection(0);
            // Clear saved selection since the preset no longer exists
            prefs.edit()
                    .putString(key(Constant.XFlag.SELECTED_PRESET_NAME), "")
                    .apply();
        });

        new AlertDialog.Builder(activity)
                .setTitle(Constant.Name.TITLE + " — 虚拟定位")
                .setView(scrollView)
                .setPositiveButton("保存", (d, w) -> {
                    int selPos = spinner.getSelectedItemPosition();
                    String selName = selPos > 0 ? names.get(selPos) : "";
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
                            .putString(key(Constant.XFlag.SELECTED_PRESET_NAME), selName)
                            .apply();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** Builds a {@link LocationPreset} from the current contents of the editing fields. */
    private static LocationPreset buildPresetFromFields(
            String name,
            EditText etLat, EditText etLon, EditText etOffset,
            EditText etWifiSsid, EditText etWifiBssid, EditText etWifiMac,
            EditText etCellId, EditText etCellLac, EditText etCellMcc, EditText etCellMnc) {
        LocationPreset p = new LocationPreset();
        p.name      = name;
        p.latitude  = etLat.getText().toString().trim();
        p.longitude = etLon.getText().toString().trim();
        p.offset    = etOffset.getText().toString().trim();
        p.wifiSsid  = etWifiSsid.getText().toString().trim();
        p.wifiBssid = etWifiBssid.getText().toString().trim();
        p.wifiMac   = etWifiMac.getText().toString().trim();
        p.cellId    = etCellId.getText().toString().trim();
        p.cellLac   = etCellLac.getText().toString().trim();
        p.cellMcc   = etCellMcc.getText().toString().trim();
        p.cellMnc   = etCellMnc.getText().toString().trim();
        return p;
    }

    /** Loads the list of saved address presets from SharedPreferences. */
    private static List<LocationPreset> loadPresets(SharedPreferences prefs) {
        List<LocationPreset> list = new ArrayList<>();
        String json = prefs.getString(key(Constant.XFlag.ADDRESS_PRESETS), "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(LocationPreset.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            android.util.Log.w("DingDingPlugin", "loadPresets: failed to parse presets JSON", e);
        }
        return list;
    }

    /** Persists the list of address presets to SharedPreferences. */
    private static void savePresets(SharedPreferences prefs, List<LocationPreset> presets) {
        JSONArray arr = new JSONArray();
        for (LocationPreset p : presets) {
            try {
                arr.put(p.toJson());
            } catch (JSONException e) {
                android.util.Log.w("DingDingPlugin", "savePresets: failed to serialize preset '" + p.name + "'", e);
            }
        }
        prefs.edit()
                .putString(key(Constant.XFlag.ADDRESS_PRESETS), arr.toString())
                .apply();
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

    public static class Build {

        private XPluginManager mPluginManager;

        public Build(XPluginManager pluginManager) {
            mPluginManager = pluginManager;
        }

        public XPlugin build() {
            return new DingDingPlugin(this);
        }
    }
}
