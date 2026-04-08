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

package com.sky.xposed.rimet.plugin.system;

import android.content.SharedPreferences;
import android.util.Log;

import com.sky.xposed.rimet.Constant;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * Deep hooks for Alipay's map/location model layer ({@code com.alipay.mobile.map.model}).
 *
 * <p>These classes are bundled inside DingTalk (same Alibaba group SDK lineage) and are
 * loaded via DingTalk's own ClassLoader.  All hooks are wrapped in individual try/catch
 * blocks and will not crash the host application if a class or method is absent.</p>
 *
 * <p>Classes hooked here:</p>
 * <ul>
 *   <li>{@code com.alipay.mobile.map.model.LatLonPoint} — GPS coordinate container;
 *       overrides {@code getLatitude}/{@code getLongitude}.</li>
 *   <li>{@code com.alipay.mobile.map.model.IndoorLocation} — indoor positioning;
 *       overrides {@code getFloor}.</li>
 *   <li>{@code com.alipay.mobile.map.model.LBSWifiItemInfo} — WiFi scan entry used
 *       for LBS; overrides {@code getBssid}, {@code getSsid}, {@code getLevel}.</li>
 *   <li>{@code com.alipay.mobile.map.model.SearchPoiRequest} — POI search request;
 *       intercepts {@code setKeywords} to log/modify the search term.</li>
 * </ul>
 */
public class AlipayMapHookPlugin {

    private static final String TAG = "AlipayMapHook";

    /** Mocked floor level returned by {@code IndoorLocation#getFloor} when spoofing is active. */
    private static final float DEFAULT_INDOOR_FLOOR = 1.0f;

    /**
     * Mocked WiFi signal level (dBm) returned by {@code LBSWifiItemInfo#getLevel}.
     * -30 dBm represents an extremely strong signal, simulating a nearby access point.
     */
    private static final int SPOOFED_WIFI_LEVEL = -30;

    private AlipayMapHookPlugin() {
    }

    /**
     * Install all Alipay map model hooks.
     *
     * @param module      the LibXposed module instance (used for hooking and prefs).
     * @param classLoader the DingTalk process class loader (contains Alipay SDK classes).
     */
    public static void setup(XposedModule module, ClassLoader classLoader) {
        hookLatLonPoint(module, classLoader);
        hookIndoorLocation(module, classLoader);
        hookLBSWifiItemInfo(module, classLoader);
        hookSearchPoiRequest(module, classLoader);
        Log.i(TAG, "Alipay map hooks installed");
    }

    // -----------------------------------------------------------------------
    // LatLonPoint — GPS coordinate container
    // -----------------------------------------------------------------------

    private static void hookLatLonPoint(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass(
                    "com.alipay.mobile.map.model.LatLonPoint");

            Method getLatitude = cls.getMethod("getLatitude");
            module.hook(getLatitude).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    double baseLat = Double.parseDouble(val);
                    String lonVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                    double baseLon = lonVal.isEmpty() ? 0.0 : Double.parseDouble(lonVal);
                    String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    double offsetMeters = offVal.isEmpty() ? 0.0 : Double.parseDouble(offVal);
                    SystemHookPlugin.logSpoofed(module, "LatLonPoint#getLatitude");
                    return SystemHookPlugin.getEffectiveCoords(baseLat, baseLon, offsetMeters)[0];
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method getLongitude = cls.getMethod("getLongitude");
            module.hook(getLongitude).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    String latVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                    double baseLat = latVal.isEmpty() ? 0.0 : Double.parseDouble(latVal);
                    double baseLon = Double.parseDouble(val);
                    String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    double offsetMeters = offVal.isEmpty() ? 0.0 : Double.parseDouble(offVal);
                    SystemHookPlugin.logSpoofed(module, "LatLonPoint#getLongitude");
                    return SystemHookPlugin.getEffectiveCoords(baseLat, baseLon, offsetMeters)[1];
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Log.i(TAG, "hookLatLonPoint installed");
        } catch (Exception e) {
            Log.w(TAG, "hookLatLonPoint failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // IndoorLocation — indoor positioning (floor number)
    // -----------------------------------------------------------------------

    private static void hookIndoorLocation(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass(
                    "com.alipay.mobile.map.model.IndoorLocation");

            Method getFloor = cls.getMethod("getFloor");
            module.hook(getFloor).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                SystemHookPlugin.logSpoofed(module, "IndoorLocation#getFloor");
                return DEFAULT_INDOOR_FLOOR;
            });

            Log.i(TAG, "hookIndoorLocation installed");
        } catch (Exception e) {
            Log.w(TAG, "hookIndoorLocation failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // LBSWifiItemInfo — WiFi scan entry used for LBS positioning
    // -----------------------------------------------------------------------

    private static void hookLBSWifiItemInfo(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass(
                    "com.alipay.mobile.map.model.LBSWifiItemInfo");

            Method getBssid = cls.getMethod("getBssid");
            module.hook(getBssid).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String bssid = SystemHookPlugin.getString(prefs, Constant.XFlag.WIFI_BSSID);
                if (bssid.isEmpty()) return chain.proceed();
                SystemHookPlugin.logSpoofed(module, "LBSWifiItemInfo#getBssid");
                return bssid;
            });

            Method getSsid = cls.getMethod("getSsid");
            module.hook(getSsid).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String ssid = SystemHookPlugin.getString(prefs, Constant.XFlag.WIFI_SSID);
                if (ssid.isEmpty()) return chain.proceed();
                SystemHookPlugin.logSpoofed(module, "LBSWifiItemInfo#getSsid");
                return ssid;
            });

            Method getLevel = cls.getMethod("getLevel");
            module.hook(getLevel).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                SystemHookPlugin.logSpoofed(module, "LBSWifiItemInfo#getLevel");
                return SPOOFED_WIFI_LEVEL;
            });

            Log.i(TAG, "hookLBSWifiItemInfo installed");
        } catch (Exception e) {
            Log.w(TAG, "hookLBSWifiItemInfo failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // SearchPoiRequest — POI keyword search interception
    // -----------------------------------------------------------------------

    private static void hookSearchPoiRequest(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass(
                    "com.alipay.mobile.map.model.SearchPoiRequest");

            Method setKeywords = cls.getMethod("setKeywords", String.class);
            module.hook(setKeywords).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String original = (String) chain.getArg(0);
                Log.i(TAG, "SearchPoiRequest#setKeywords intercepted: " + original);
                return chain.proceed();
            });

            Log.i(TAG, "hookSearchPoiRequest installed");
        } catch (Exception e) {
            Log.w(TAG, "hookSearchPoiRequest failed: " + e.getMessage());
        }
    }
}
