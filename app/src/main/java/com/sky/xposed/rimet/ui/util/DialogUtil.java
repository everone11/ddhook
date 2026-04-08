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

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import com.sky.xposed.rimet.BuildConfig;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.* utilities with standard Android APIs,
 * removed Picasso image-loading (QQ/donate images not shipped in this build).
 */
public class DialogUtil {

    private static final String TAG = "DialogUtil";

    private DialogUtil() {
    }

    public static void showMessage(Context context, String message) {
        showMessage(context, "提示", message, "确定", null, true);
    }

    public static void showMessage(Context context, String title, String message,
                                   String positiveText, DialogInterface.OnClickListener listener,
                                   boolean cancel) {
        if (context == null || TextUtils.isEmpty(message) || TextUtils.isEmpty(positiveText)) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(cancel)
                .setPositiveButton(positiveText, listener)
                .show();
    }

    /**
     * 显示关于对话框
     */
    public static void showAboutDialog(Context context) {
        try {
            new AlertDialog.Builder(context)
                    .setTitle("关于")
                    .setMessage("当前版本：v" + BuildConfig.VERSION_NAME
                            + "\n\n钉钉助手 (ddhook)\n基于 libxposed API 的钉钉功能增强模块"
                            + "\n\n源码: github.com/everone11/ddhook")
                    .setPositiveButton("确定", (dialog, which) -> dialog.dismiss())
                    .show();
        } catch (Throwable tr) {
            Log.e(TAG, "showAboutDialog 异常", tr);
        }
    }
}
