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
import java.util.Collections;

import io.github.libxposed.api.XposedModule;

/**
 * Deep hooks for DingTalk's internal AMap location pipeline.
 *
 * <p>All hooks are wrapped in try/catch and will not crash the target app if a
 * class or method is absent (e.g. due to obfuscation or DingTalk version changes).
 * Load these hooks after the DingTalk {@link ClassLoader} is available, i.e. from
 * {@code Main.onPackageReady}.</p>
 *
 * <p>Classes hooked here:</p>
 * <ul>
 *   <li>{@code com.alibaba.android.dingtalkbase.amap.GMapLocation} — DingTalk's
 *       {@code android.location.Location} subclass; overrides getLatitude/getLongitude.</li>
 *   <li>{@code com.alibaba.android.dingtalkbase.amap.LocationProxy} — caches and
 *       dispatches {@code AMapLocation} via {@code onLocationChanged}.</li>
 *   <li>{@code com.alibaba.wireless.security.aopsdk.replace.android.net.wifi.WifiManager}
 *       — AOP replacement class that DingTalk uses instead of the system WifiManager
 *       for getScanResults / startScan, bypassing system-level hooks.</li>
 * </ul>
 */
public class DingTalkDeepHookPlugin {

    private static final String TAG = "DingTalkDeepHook";

    private DingTalkDeepHookPlugin() {
    }

    /**
     * Install all DingTalk-specific deep hooks.
     *
     * @param module      the LibXposed module instance (used for hooking and prefs).
     * @param classLoader the DingTalk process class loader.
     */
    public static void setup(XposedModule module, ClassLoader classLoader) {
        hookGMapLocation(module, classLoader);
        hookLocationProxy(module, classLoader);
        hookAopWifiManager(module, classLoader);
        Log.i(TAG, "DingTalk deep hooks installed");
    }

    // -----------------------------------------------------------------------
    // GMapLocation — DingTalk's Location subclass
    // -----------------------------------------------------------------------

    private static void hookGMapLocation(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass(
                    "com.alibaba.android.dingtalkbase.amap.GMapLocation");

            Method getLatitude = cls.getMethod("getLatitude");
            module.hook(getLatitude).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    SystemHookPlugin.logSpoofed("GMapLocation#getLatitude");
                    return Double.parseDouble(val);
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
                    SystemHookPlugin.logSpoofed("GMapLocation#getLongitude");
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method setLatitude = cls.getMethod("setLatitude", double.class);
            module.hook(setLatitude).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return chain.proceed(new Object[]{Double.parseDouble(val)});
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method setLongitude = cls.getMethod("setLongitude", double.class);
            module.hook(setLongitude).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return chain.proceed(new Object[]{Double.parseDouble(val)});
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Log.i(TAG, "hookGMapLocation installed");
        } catch (Exception e) {
            Log.w(TAG, "hookGMapLocation failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // LocationProxy — intercepts AMapLocation before DingTalk caches it
    // -----------------------------------------------------------------------

    private static void hookLocationProxy(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> aMapLocationCls = classLoader.loadClass(
                    "com.amap.api.location.AMapLocation");
            Class<?> proxyCls = classLoader.loadClass(
                    "com.alibaba.android.dingtalkbase.amap.LocationProxy");

            // Cache the setter Methods for reuse on every onLocationChanged call.
            final Method setLat = aMapLocationCls.getMethod("setLatitude", double.class);
            final Method setLon = aMapLocationCls.getMethod("setLongitude", double.class);

            Method onLocationChanged = proxyCls.getMethod(
                    "onLocationChanged", aMapLocationCls);

            module.hook(onLocationChanged).intercept(chain -> {
                SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();

                Object aMapLocation = chain.getArg(0);
                if (aMapLocation == null) return chain.proceed();

                // Apply lat/lon overrides independently (consistent with getter hooks).
                String latStr = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                String lonStr = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                boolean patched = false;
                try {
                    if (!latStr.isEmpty()) {
                        setLat.invoke(aMapLocation, Double.parseDouble(latStr));
                        patched = true;
                    }
                    if (!lonStr.isEmpty()) {
                        setLon.invoke(aMapLocation, Double.parseDouble(lonStr));
                        patched = true;
                    }
                } catch (Exception ignored) {
                    // Reflection failure — proceed with original location.
                }
                if (patched) {
                    SystemHookPlugin.logSpoofed("LocationProxy#onLocationChanged");
                }
                return chain.proceed();
            });

            Log.i(TAG, "hookLocationProxy installed");
        } catch (Exception e) {
            Log.w(TAG, "hookLocationProxy failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // AOP WifiManager wrapper — DingTalk bypasses system WifiManager via this
    // -----------------------------------------------------------------------

    private static void hookAopWifiManager(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> aopCls = classLoader.loadClass(
                    "com.alibaba.wireless.security.aopsdk.replace.android.net.wifi.WifiManager");

            // getScanResults(android.net.wifi.WifiManager) — returns List<ScanResult>
            try {
                Class<?> wifiManagerCls = Class.forName("android.net.wifi.WifiManager");
                Method getScanResults = aopCls.getMethod("getScanResults", wifiManagerCls);
                module.hook(getScanResults).intercept(chain -> {
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                    SystemHookPlugin.logSpoofed("AopWifiManager#getScanResults");
                    return Collections.emptyList();
                });
                Log.i(TAG, "hookAopWifiManager#getScanResults installed");
            } catch (Exception e) {
                Log.w(TAG, "hookAopWifiManager#getScanResults failed: " + e.getMessage());
            }

            // startScan(android.net.wifi.WifiManager) — returns boolean
            try {
                Class<?> wifiManagerCls = Class.forName("android.net.wifi.WifiManager");
                Method startScan = aopCls.getMethod("startScan", wifiManagerCls);
                module.hook(startScan).intercept(chain -> {
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                    return true;
                });
                Log.i(TAG, "hookAopWifiManager#startScan installed");
            } catch (Exception e) {
                Log.w(TAG, "hookAopWifiManager#startScan failed: " + e.getMessage());
            }

        } catch (Exception e) {
            Log.w(TAG, "hookAopWifiManager class not found: " + e.getMessage());
        }
    }
}
