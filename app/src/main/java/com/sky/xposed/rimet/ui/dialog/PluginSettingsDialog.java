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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.sky.xposed.rimet.BuildConfig;
import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.plugin.interfaces.XPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;
import com.sky.xposed.rimet.plugin.PluginManager;
import com.sky.xposed.rimet.ui.adapter.PluginSettingsAdapter;
import com.sky.xposed.rimet.ui.base.BaseDialog;
import com.sky.xposed.rimet.ui.util.DialogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced CommonFrameLayout / TitleView (xposed-common) with plain
 * LinearLayout, replaced Picasso icon-loading with Android built-in drawables.
 * Note: this dialog is intended to be shown inside the hooked app's process where
 * PluginManager.getInstance() is non-null. Showing it from the module's own UI
 * will result in an empty plugin list.
 */
public class PluginSettingsDialog extends BaseDialog
        implements View.OnClickListener, AdapterView.OnItemClickListener {

    private TextView mTitleView;
    private ImageButton mMoreButton;
    private ListView mListView;
    private PluginSettingsAdapter mAdapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (getDialog() != null) {
            getDialog().requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        // ── Title bar ──────────────────────────────────────────────────────
        LinearLayout titleBar = new LinearLayout(requireContext());
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(Constant.Color.TOOLBAR);
        int barH = dp(56);
        titleBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, barH));
        int hPad = dp(12);
        titleBar.setPadding(hPad, 0, hPad, 0);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        mTitleView = new TextView(requireContext());
        mTitleView.setText(Constant.Name.TITLE);
        mTitleView.setTextColor(0xFFFFFFFF);
        mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mTitleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(mTitleView);

        mMoreButton = new ImageButton(requireContext());
        mMoreButton.setImageResource(android.R.drawable.ic_menu_more);
        mMoreButton.setBackgroundColor(0x00000000);
        mMoreButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_ATOP));
        mMoreButton.setOnClickListener(this);
        titleBar.addView(mMoreButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        root.addView(titleBar);

        // ── Plugin list ────────────────────────────────────────────────────
        mListView = new ListView(requireContext());
        mListView.setCacheColorHint(0x00000000);
        mListView.setDividerHeight(0);
        mListView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));
        mListView.setOnItemClickListener(this);
        root.addView(mListView);

        return root;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAdapter = new PluginSettingsAdapter(requireContext());
        mAdapter.setItems(getXPlugins());
        mListView.setAdapter(mAdapter);
    }

    @Override
    public void onClick(View v) {
        if (v == mMoreButton) {
            showMoreMenu();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        XPluginManager pluginManager = PluginManager.getInstance();
        if (pluginManager == null) return;

        XPlugin.Info info = mAdapter.getItem(position);
        XPlugin xPlugin = pluginManager.getXPlugin(info);
        if (xPlugin != null && getActivity() != null) {
            xPlugin.openSettings(getActivity());
        }
    }

    private List<XPlugin.Info> getXPlugins() {
        List<XPlugin.Info> infos = new ArrayList<>();
        XPluginManager pluginManager = PluginManager.getInstance();
        if (pluginManager == null) return infos;

        List<XPlugin> xPlugins = pluginManager.getXPlugins(Constant.Flag.MAIN);
        for (XPlugin xPlugin : xPlugins) {
            infos.add(xPlugin.getInfo());
        }
        return infos;
    }

    private void showMoreMenu() {
        PopupMenu popupMenu = new PopupMenu(requireContext(), mMoreButton, Gravity.END);
        Menu menu = popupMenu.getMenu();
        menu.add(1, 1, 1, "关于");
        popupMenu.setOnMenuItemClickListener(item -> {
            handleMoreMenu(item);
            return true;
        });
        popupMenu.show();
    }

    private void handleMoreMenu(MenuItem item) {
        if (item.getItemId() == 1) {
            DialogUtil.showMessage(requireContext(),
                    "\n程序版本: v" + BuildConfig.VERSION_NAME);
        }
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                requireContext().getResources().getDisplayMetrics());
    }
}
