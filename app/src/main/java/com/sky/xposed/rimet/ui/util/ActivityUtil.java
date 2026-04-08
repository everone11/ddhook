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
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.util.Alog with android.util.Log,
 * replaced com.sky.xposed.common.util.ToastUtil with android.widget.Toast.
 */
public class ActivityUtil {

    private static final String TAG = "ActivityUtil";

    static final String ALI_PAY_URI =
            "alipayqr://platformapi/startapp?saId=10000007&clientVersion=3.7.0.0718&qrcode=";

    private ActivityUtil() {
    }

    public static boolean startActivity(Context context, Intent intent) {
        try {
            String packageName = intent.getPackage();
            if (!TextUtils.isEmpty(packageName)
                    && !TextUtils.equals(packageName, context.getPackageName())) {
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "启动Activity异常", e);
        }
        return false;
    }

    /**
     * 启动支付宝
     */
    public static boolean startAlipay(Context context, String payUrl) {
        try {
            Intent intent = new Intent("android.intent.action.VIEW");
            intent.setData(Uri.parse(ALI_PAY_URI + payUrl));
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
            } else {
                intent.setData(Uri.parse(payUrl));
                context.startActivity(intent);
            }
            return true;
        } catch (Throwable tr) {
            Log.e(TAG, "启动失败", tr);
            return false;
        }
    }
}
