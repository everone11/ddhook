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

package com.sky.xposed.rimet.ui.adapter;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.sky.xposed.rimet.plugin.interfaces.XPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced com.sky.xposed.common.ui.base.BaseListAdapter with standard
 * {@link BaseAdapter}, replaced CommentItemView with a simple {@link TextView},
 * replaced DisplayUtil.dip2px with TypedValue dp-to-px conversion.
 */
public class PluginSettingsAdapter extends BaseAdapter {

    private final Context mContext;
    private List<XPlugin.Info> mItems = new ArrayList<>();

    public PluginSettingsAdapter(Context context) {
        mContext = context;
    }

    public void setItems(List<XPlugin.Info> items) {
        mItems = items != null ? items : new ArrayList<>();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public XPlugin.Info getItem(int position) {
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view;
        if (convertView instanceof TextView) {
            view = (TextView) convertView;
        } else {
            view = new TextView(mContext);
            int height = dpToPx(32f);
            view.setHeight(height);
            view.setTextSize(16f);
            view.setMaxLines(1);
            int padding = dpToPx(16f);
            view.setPadding(padding, 0, padding, 0);
        }
        XPlugin.Info item = getItem(position);
        if (item != null) {
            view.setText(item.getName());
        }
        return view;
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                mContext.getResources().getDisplayMetrics());
    }
}
