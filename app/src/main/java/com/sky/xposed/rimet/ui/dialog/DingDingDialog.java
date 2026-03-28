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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.ui.util.DialogUtil;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced xposed-common custom views (SwitchItemView, EditTextItemView,
 * SimpleItemView) with standard SwitchCompat / EditText / TextView.
 * Settings are read/written directly via SharedPreferences instead of going through
 * PluginManager (which is only available in the hooked app's process).
 */
public class DingDingDialog extends CommonDialog {

    private SwitchCompat mSwLucky;
    private EditText mEtLuckyDelayed;
    private SwitchCompat mSwFastLucky;
    private SwitchCompat mSwRecall;
    private TextView mTvDonate;
    private TextView mTvAbout;

    @Override
    public void createView(LinearLayout container) {
        mSwLucky    = addSwitch(container, "自动接收红包", "开启时自动接收红包");
        mEtLuckyDelayed = addEditRow(container, "红包延迟时间 (秒)");
        mSwFastLucky = addSwitch(container, "快速打开红包", "开启时快速打开红包");
        mSwRecall   = addSwitch(container, "消息防撤回", "开启时消息不会被撤回");
        mTvDonate   = addSimpleItem(container, "支持我们");
        mTvAbout    = addSimpleItem(container, "关于");
    }

    @Override
    protected void initView(View view, Bundle args) {
        super.initView(view, args);
        setTitle(Constant.Name.TITLE);

        SharedPreferences prefs = getDefaultSharedPreferences();

        mSwLucky.setChecked(prefs.getBoolean(
                Integer.toString(Constant.XFlag.ENABLE_LUCKY), true));
        mSwLucky.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(
                        Integer.toString(Constant.XFlag.ENABLE_LUCKY), checked).apply());

        mEtLuckyDelayed.setText(prefs.getString(
                Integer.toString(Constant.XFlag.LUCKY_DELAYED), ""));
        mEtLuckyDelayed.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(
                        Integer.toString(Constant.XFlag.LUCKY_DELAYED), s.toString()).apply();
            }
        });

        mSwFastLucky.setChecked(prefs.getBoolean(
                Integer.toString(Constant.XFlag.ENABLE_FAST_LUCKY), true));
        mSwFastLucky.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(
                        Integer.toString(Constant.XFlag.ENABLE_FAST_LUCKY), checked).apply());

        mSwRecall.setChecked(prefs.getBoolean(
                Integer.toString(Constant.XFlag.ENABLE_RECALL), true));
        mSwRecall.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(
                        Integer.toString(Constant.XFlag.ENABLE_RECALL), checked).apply());

        mTvDonate.setOnClickListener(v -> {
            DonateDialog donateDialog = new DonateDialog();
            donateDialog.show(getChildFragmentManager(), "donate");
        });

        mTvAbout.setOnClickListener(v -> DialogUtil.showAboutDialog(requireContext()));
    }

    /**
     * Appends a labelled EditText row (number input) and returns the EditText.
     */
    private EditText addEditRow(LinearLayout container, String label) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(16);
        row.setPadding(pad, dp(12), pad, dp(12));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvLabel.setTextColor(0xFF333333);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        EditText et = new EditText(requireContext());
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setMaxLines(1);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
        et.setHint("0");
        et.setGravity(Gravity.CENTER);
        et.setLayoutParams(new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(et);

        container.addView(row);

        View divider = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(16);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(0xFFE0E0E0);
        container.addView(divider);

        return et;
    }
}
