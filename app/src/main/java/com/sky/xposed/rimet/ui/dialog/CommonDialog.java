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

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.ui.base.BaseDialog;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.ui.view.CommonFrameLayout / TitleView
 * with a simple two-section LinearLayout (title bar + scrollable content),
 * removed Picasso dependency (back button uses a built-in Android drawable).
 */
public abstract class CommonDialog extends BaseDialog {

    private TextView mTitleView;
    private LinearLayout mContentContainer;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (getDialog() != null) {
            getDialog().requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        // Root
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Title bar ──────────────────────────────────────────────────────
        LinearLayout titleBar = new LinearLayout(requireContext());
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(Constant.Color.TOOLBAR);
        int barHeight = dp(56);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barHeight));
        int hPad = dp(12);
        titleBar.setPadding(hPad, 0, hPad, 0);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton backBtn = new ImageButton(requireContext());
        backBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        backBtn.setBackgroundColor(0x00000000);
        backBtn.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_ATOP));
        backBtn.setOnClickListener(v -> dismiss());
        titleBar.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        mTitleView = new TextView(requireContext());
        mTitleView.setTextColor(0xFFFFFFFF);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(8);
        mTitleView.setLayoutParams(titleParams);
        titleBar.addView(mTitleView);

        root.addView(titleBar);

        // ── Scrollable content ────────────────────────────────────────────
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mContentContainer = new LinearLayout(requireContext());
        mContentContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView.addView(mContentContainer);
        root.addView(scrollView);

        // Let subclasses populate mContentContainer
        createView(mContentContainer);

        return root;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView(view, getArguments());
    }

    /**
     * Subclasses add their content views here (called during {@link #onCreateView}).
     */
    public abstract void createView(LinearLayout container);

    /**
     * Subclasses bind data/listeners here (called during {@link #onViewCreated}).
     */
    protected void initView(View view, Bundle args) {
        // Default: nothing. Subclasses override and call super.
    }

    public void setTitle(String title) {
        if (mTitleView != null) mTitleView.setText(title);
    }

    public LinearLayout getContentContainer() {
        return mContentContainer;
    }

    protected int dp(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                requireContext().getResources().getDisplayMetrics());
    }

    /**
     * Creates a horizontal switch-row and appends it to {@code container}.
     * Returns the {@link android.widget.Switch} so callers can bind it.
     */
    protected androidx.appcompat.widget.SwitchCompat addSwitch(
            LinearLayout container, String title, String desc) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(64));
        int h = dp(16);
        row.setPadding(h, h, h, h);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textBlock = new LinearLayout(requireContext());
        textBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tbParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textBlock.setLayoutParams(tbParams);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setTextColor(0xFF333333);
        textBlock.addView(tvTitle);

        if (desc != null && !desc.isEmpty()) {
            TextView tvDesc = new TextView(requireContext());
            tvDesc.setText(desc);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvDesc.setTextColor(0xFF888888);
            textBlock.addView(tvDesc);
        }
        row.addView(textBlock);

        androidx.appcompat.widget.SwitchCompat sw =
                new androidx.appcompat.widget.SwitchCompat(requireContext());
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(row);
        addDivider(container);
        return sw;
    }

    /**
     * Creates a clickable text-row and appends it to {@code container}.
     */
    protected TextView addSimpleItem(LinearLayout container, String title) {
        TextView tv = new TextView(requireContext());
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(0xFF333333);
        int pad = dp(16);
        tv.setPadding(pad, dp(18), pad, dp(18));
        tv.setClickable(true);
        int[] attrs = {android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta =
                requireContext().obtainStyledAttributes(attrs);
        tv.setBackground(ta.getDrawable(0));
        ta.recycle();
        container.addView(tv);
        addDivider(container);
        return tv;
    }

    private void addDivider(LinearLayout container) {
        View divider = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(16);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(0xFFE0E0E0);
        container.addView(divider);
    }
}
