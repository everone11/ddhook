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

package com.sky.xposed.rimet.ui.activity;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.sky.xposed.rimet.BuildConfig;
import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.R;
import com.sky.xposed.rimet.data.VersionManager;
import com.sky.xposed.rimet.plugin.interfaces.XVersionManager;

/**
 * Main settings Activity for the ddhook Xposed module.
 *
 * <p>Migrated and modernized from {@code mikecraig6810/xposed-rimet}'s MainActivity.
 * Uses standard {@link androidx.appcompat.widget.SwitchCompat} instead of the
 * {@code xposed-common} custom views, making the app self-contained.</p>
 *
 * <p>Settings are saved to the module's own {@link SharedPreferences} under the
 * file name {@link Constant.Name#RIMET}. The hook code running in DingTalk's process
 * reads these preferences via {@code createPackageContext} so changes take effect
 * on the next DingTalk launch.</p>
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        initInfoSection();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, R.string.about_title);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initInfoSection() {
        TextView tvPluginVersion = findViewById(R.id.tv_plugin_version);
        TextView tvDingTalkVersion = findViewById(R.id.tv_dingtalk_version);
        TextView tvSupportStatus = findViewById(R.id.tv_support_status);

        tvPluginVersion.setText("v" + BuildConfig.VERSION_NAME);
        tvDingTalkVersion.setText(getDingTalkVersionName());

        XVersionManager versionManager = new VersionManager.Build(getApplicationContext()).build();
        if (versionManager.isSupportVersion()) {
            tvSupportStatus.setText(getString(R.string.label_supported));
            tvSupportStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            tvSupportStatus.setText(
                    getString(R.string.label_unsupported) + "\n"
                    + getString(R.string.label_supported_versions, versionManager.getSupportVersion()));
            tvSupportStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        }
    }

    private String getDingTalkVersionName() {
        try {
            PackageInfo info;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                info = getPackageManager().getPackageInfo(
                        Constant.Rimet.PACKAGE_NAME,
                        PackageManager.PackageInfoFlags.of(0));
            } else {
                //noinspection deprecation
                info = getPackageManager().getPackageInfo(
                        Constant.Rimet.PACKAGE_NAME, 0);
            }
            return "v" + info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.label_unknown);
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
