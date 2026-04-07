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
        hookAMapLocationClient(module, classLoader);
        hookAopWifiManager(module, classLoader);
        module.log(Log.INFO, TAG, "DingTalk deep hooks installed");
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
                    SystemHookPlugin.logSpoofed(module, "GMapLocation#getLatitude");
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
                    SystemHookPlugin.logSpoofed(module, "GMapLocation#getLongitude");
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

            module.log(Log.INFO, TAG, "hookGMapLocation installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookGMapLocation failed", e);
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
                    SystemHookPlugin.logSpoofed(module, "LocationProxy#onLocationChanged");
                }
                return chain.proceed();
            });

            module.log(Log.INFO, TAG, "hookLocationProxy installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookLocationProxy failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // AMapLocationClient — intercepts location at the AMap SDK layer
    // -----------------------------------------------------------------------

    private static void hookAMapLocationClient(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> aMapLocationCls = classLoader.loadClass(
                    "com.amap.api.location.AMapLocation");
            Class<?> clientCls = classLoader.loadClass(
                    "com.amap.api.location.AMapLocationClient");
            Class<?> listenerCls = classLoader.loadClass(
                    "com.amap.api.location.AMapLocationListener");

            final Method setLatMethod = aMapLocationCls.getMethod("setLatitude", double.class);
            final Method setLonMethod = aMapLocationCls.getMethod("setLongitude", double.class);

            // getLastKnownLocation() — return null to invalidate the cached real location,
            // forcing the app to rely on the live listener path where our hooks are active.
            try {
                Method getLastKnownLocation = clientCls.getMethod("getLastKnownLocation");
                module.hook(getLastKnownLocation).intercept(chain -> {
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                    String lat = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                    if (lat.isEmpty()) return chain.proceed();
                    SystemHookPlugin.logSpoofed(module, "AMapLocationClient#getLastKnownLocation");
                    return null;
                });
                module.log(Log.INFO, TAG, "hookAMapLocationClient#getLastKnownLocation installed");
            } catch (Throwable e) {
                module.log(Log.WARN, TAG, "hookAMapLocationClient#getLastKnownLocation failed", e);
            }

            // AMapLocation.getLatitude/getLongitude — hook getters directly using the app
            // classloader (SystemHookPlugin.hookAMapLocation used the wrong classloader and
            // always failed with ClassNotFoundException).
            try {
                Method getLatitude = aMapLocationCls.getMethod("getLatitude");
                module.hook(getLatitude).intercept(chain -> {
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                    String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                    if (val.isEmpty()) return chain.proceed();
                    try {
                        SystemHookPlugin.logSpoofed(module, "AMapLocation#getLatitude");
                        return Double.parseDouble(val);
                    } catch (NumberFormatException e) {
                        return chain.proceed();
                    }
                });

                Method getLongitude = aMapLocationCls.getMethod("getLongitude");
                module.hook(getLongitude).intercept(chain -> {
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                    String val = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                    if (val.isEmpty()) return chain.proceed();
                    try {
                        SystemHookPlugin.logSpoofed(module, "AMapLocation#getLongitude");
                        return Double.parseDouble(val);
                    } catch (NumberFormatException e) {
                        return chain.proceed();
                    }
                });
                module.log(Log.INFO, TAG, "hookAMapLocation getters installed");
            } catch (Throwable e) {
                module.log(Log.WARN, TAG, "hookAMapLocation getters failed", e);
            }

            // setLocationListener(AMapLocationListener) — wrap the real listener in a proxy
            // so that every onLocationChanged callback has its coordinates replaced before
            // the DingTalk code sees them.  This is the SDK-level analogue of the legacy
            // xposed-rimet AMapLocationListenerProxy approach.
            try {
                Method setLocationListener = clientCls.getMethod(
                        "setLocationListener", listenerCls);
                module.hook(setLocationListener).intercept(chain -> {
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
                    String lat = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                    if (lat.isEmpty()) return chain.proceed();

                    Object realListener = chain.getArg(0);
                    if (realListener == null) return chain.proceed();
                    // Avoid double-wrapping if already proxied.
                    if (java.lang.reflect.Proxy.isProxyClass(realListener.getClass())) {
                        return chain.proceed();
                    }

                    Object proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                            classLoader,
                            new Class[]{listenerCls},
                            (proxy, method, args) -> {
                                if ("onLocationChanged".equals(method.getName())
                                        && args != null && args.length == 1
                                        && args[0] != null) {
                                    SharedPreferences p = SystemHookPlugin.getPrefs(module);
                                    if (SystemHookPlugin.isEnabled(p)) {
                                        String latStr = SystemHookPlugin.getString(
                                                p, Constant.XFlag.LATITUDE);
                                        String lonStr = SystemHookPlugin.getString(
                                                p, Constant.XFlag.LONGITUDE);
                                        try {
                                            if (!latStr.isEmpty()) {
                                                setLatMethod.invoke(
                                                        args[0], Double.parseDouble(latStr));
                                            }
                                            if (!lonStr.isEmpty()) {
                                                setLonMethod.invoke(
                                                        args[0], Double.parseDouble(lonStr));
                                            }
                                            SystemHookPlugin.logSpoofed(module,
                                                    "AMapLocationListener#onLocationChanged");
                                        } catch (Exception e) {
                                            module.log(Log.WARN, TAG,
                                                    "AMapLocationListener proxy patch failed", e);
                                        }
                                    }
                                }
                                return method.invoke(realListener, args);
                            });

                    return chain.proceed(new Object[]{proxyListener});
                });
                module.log(Log.INFO, TAG,
                        "hookAMapLocationClient#setLocationListener installed");
            } catch (Throwable e) {
                module.log(Log.WARN, TAG,
                        "hookAMapLocationClient#setLocationListener failed", e);
            }

        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookAMapLocationClient setup failed", e);
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
                    SystemHookPlugin.logSpoofed(module, "AopWifiManager#getScanResults");
                    return Collections.emptyList();
                });
                module.log(Log.INFO, TAG, "hookAopWifiManager#getScanResults installed");
            } catch (Throwable e) {
                module.log(Log.WARN, TAG, "hookAopWifiManager#getScanResults failed", e);
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
                module.log(Log.INFO, TAG, "hookAopWifiManager#startScan installed");
            } catch (Throwable e) {
                module.log(Log.WARN, TAG, "hookAopWifiManager#startScan failed", e);
            }

        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookAopWifiManager class not found", e);
        }
    }
}
