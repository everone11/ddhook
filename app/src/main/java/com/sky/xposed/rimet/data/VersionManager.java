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

package com.sky.xposed.rimet.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.config.RimetConfig4617;
import com.sky.xposed.rimet.data.config.RimetConfig830;
import com.sky.xposed.rimet.data.config.RimetConfig750;
import com.sky.xposed.rimet.plugin.interfaces.XConfig;
import com.sky.xposed.rimet.plugin.interfaces.XVersionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


/**
 * Created by sky on 2018/9/24.
 *
 * Hook应用相关版本变量管理类
 *
 * <p>Version lookup uses exact match first.  If no exact match is found,
 * {@link #getSupportConfig()} falls back to the config for the closest
 * <em>older</em> known version.  This means the module stays functional on
 * minor / patch releases of DingTalk that have not been explicitly mapped,
 * e.g. a user on 8.3.5 will automatically receive the 8.3.0 config.</p>
 */
public class VersionManager implements XVersionManager {

    private static final String TAG = "VersionManager";

    private final static Map<String, Class<? extends XConfig>> CONFIG_MAP = new HashMap<>();

    /**
     * Sorted (ascending) map of numeric version tuples → config class.
     * Used by the nearest-older-version fallback in {@link #getSupportConfig()}.
     */
    private static final TreeMap<long[], Class<? extends XConfig>> SORTED_CONFIGS =
            new TreeMap<>((a, b) -> {
                for (int i = 0; i < Math.max(a.length, b.length); i++) {
                    long av = i < a.length ? a[i] : 0;
                    long bv = i < b.length ? b[i] : 0;
                    if (av != bv) return Long.compare(av, bv);
                }
                return 0;
            });

    static {
        // DingTalk version configurations
        CONFIG_MAP.put("4.6.17", RimetConfig4617.class);
        // 7.5.0 uses the same config as 4.6.17 as an initial compatibility mapping
        CONFIG_MAP.put("7.5.0", RimetConfig750.class);
        // DingTalk 8.3.0
        CONFIG_MAP.put("8.3.0", RimetConfig830.class);

        // Build the sorted lookup used for the nearest-older-version fallback.
        for (Map.Entry<String, Class<? extends XConfig>> entry : CONFIG_MAP.entrySet()) {
            long[] key = parseVersion(entry.getKey());
            if (key != null) SORTED_CONFIGS.put(key, entry.getValue());
        }
    }

    private XConfig mVersionConfig;
    private VersionInfo mVersionInfo;

    private VersionManager(Build build) {

        try {
            PackageInfo packageInfo = build.mContext.getPackageManager()
                    .getPackageInfo(Constant.Rimet.PACKAGE_NAME, 0);

            if (packageInfo != null) {
                mVersionInfo = new VersionInfo();
                mVersionInfo.versionName = packageInfo.versionName;
                mVersionInfo.versionCode = packageInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "DingTalk not installed: " + e.getMessage());
        }
    }

    /**
     * 获取当前版本名
     */
    @Override
    public String getVersionName() {
        return mVersionInfo != null ? mVersionInfo.versionName : "";
    }

    /**
     * 获取当前版本号
     */
    @Override
    public int getVersionCode() {
        return mVersionInfo != null ? mVersionInfo.versionCode : 0;
    }

    /**
     * 判断Hook是否支持当前版本
     *
     * <p>Returns {@code true} if an exact match OR a nearest-older-version fallback is
     * available for the currently installed DingTalk version.</p>
     */
    @Override
    public boolean isSupportVersion() {
        return getSupportConfig() != null;
    }

    /**
     * 获取支持版本的配置信息,如果没有适配到则回退到最近旧版配置,仍无匹配则返回 Null
     *
     * <p>Lookup order:</p>
     * <ol>
     *   <li>Exact version-name match in {@link #CONFIG_MAP}.</li>
     *   <li>Nearest <em>older</em> version in the sorted config map (e.g. 8.3.5 → 8.3.0).</li>
     *   <li>{@code null} if the installed version is older than the earliest known config.</li>
     * </ol>
     */
    @Override
    public XConfig getSupportConfig() {
        if (mVersionConfig == null) {
            String version = getVersionName();
            // 1. Exact match
            Class<? extends XConfig> cls = CONFIG_MAP.get(version);
            if (cls == null) {
                // 2. Nearest-older-version fallback
                cls = findNearestOlderConfig(version);
                if (cls != null) {
                    Log.i(TAG, "Using nearest-older config for DingTalk " + version);
                }
            }
            mVersionConfig = getSupportConfig(cls);
        }
        return mVersionConfig;
    }

    @Override
    public List<String> getSupportVersion() {
        return new ArrayList<>(CONFIG_MAP.keySet());
    }

    /**
     * 判断Hook是否支持当前版本
     */
    public boolean isSupportVersion(String versionName) {
        return CONFIG_MAP.containsKey(versionName);
    }

    /**
     * 创建指定的配置类
     */
    private XConfig getSupportConfig(Class<? extends XConfig> vClass) {

        if (vClass == null) return null;

        try {
            return vClass.newInstance();
        } catch (Throwable tr) {
            Log.e(TAG, "创建版本配置异常", tr);
        }
        return null;
    }

    /**
     * Returns the config class for the highest known version that is ≤ {@code versionName},
     * or {@code null} if the installed version is older than every known version.
     */
    private static Class<? extends XConfig> findNearestOlderConfig(String versionName) {
        long[] target = parseVersion(versionName);
        if (target == null || SORTED_CONFIGS.isEmpty()) return null;

        Map.Entry<long[], Class<? extends XConfig>> entry = SORTED_CONFIGS.floorEntry(target);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * Parses a dot-separated version string into an array of longs.
     * Returns {@code null} if any component is non-numeric.
     * Examples: "8.3.0" → [8, 3, 0]; "4.6.17" → [4, 6, 17].
     */
    static long[] parseVersion(String version) {
        if (version == null || version.isEmpty()) return null;
        String[] parts = version.split("\\.");
        long[] nums = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                nums[i] = Long.parseLong(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return nums;
    }

    /**
     * 版本信息
     */
    private final class VersionInfo {

        protected String versionName;
        protected int versionCode;
    }

    public static final class Build {

        private final Context mContext;

        public Build(Context context) {
            mContext = context;
        }

        public XVersionManager build() {
            return new VersionManager(this);
        }
    }
}
