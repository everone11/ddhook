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
import android.widget.TextView;

import com.sky.xposed.rimet.ui.util.ActivityUtil;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: removed Picasso image-loading and donate QR-code images.
 * Provides buttons to launch Alipay and to show about information instead.
 */
public class DonateDialog extends CommonDialog {

    private TextView mTvAliPay;
    private TextView mTvWeChat;

    @Override
    public void createView(LinearLayout container) {
        mTvWeChat = addSimpleItem(container, "微信捐赠");
        mTvAliPay = addSimpleItem(container, "支付宝捐赠");
    }

    @Override
    protected void initView(View view, Bundle args) {
        super.initView(view, args);
        setTitle("支持我们");

        mTvAliPay.setOnClickListener(v -> {
            // Launch Alipay directly
            if (!ActivityUtil.startAlipay(requireContext(),
                    "HTTPS://QR.ALIPAY.COM/FKX05261FCJGZABDGWMR46")) {
                android.widget.Toast.makeText(requireContext(),
                        "启动支付宝失败", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        mTvWeChat.setOnClickListener(v ->
                android.widget.Toast.makeText(requireContext(),
                        "请通过微信扫描二维码向作者捐赠，感谢支持！",
                        android.widget.Toast.LENGTH_LONG).show());
    }
}
