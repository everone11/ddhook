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

package com.sky.xposed.rimet.ui.view;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Migrated from mikecraig6810/xposed-rimet.
 * Adapted: replaced android.support.annotation with androidx.annotation,
 * replaced com.sky.xposed.common.ui.util.LayoutUtil / DisplayUtil with
 * standard TypedValue dp-to-px conversion and manual LayoutParams.
 */
public class CategoryItemView extends FrameLayout {

    private final TextView tvName;

    public CategoryItemView(@NonNull Context context) {
        this(context, null);
    }

    public CategoryItemView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CategoryItemView(@NonNull Context context, @Nullable AttributeSet attrs,
                            int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        int height = dpToPx(context, 30f);
        setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        int vPad = dpToPx(context, 4f);
        setPadding(0, vPad, 0, vPad);

        tvName = new TextView(context);
        tvName.setTextColor(context.getColor(android.R.color.holo_blue_dark));
        tvName.setTextSize(13f);

        int leftMargin = dpToPx(context, 15f);
        LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.leftMargin = leftMargin;
        params.gravity = Gravity.CENTER_VERTICAL;
        addView(tvName, params);
    }

    public void setName(String title) {
        tvName.setText(title);
    }

    public String getName() {
        return tvName.getText().toString();
    }

    private static int dpToPx(Context context, float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }
}
