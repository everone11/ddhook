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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.sky.xposed.rimet.Constant;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * Deep hooks for DingTalk's internal AMap location pipeline, message anti-recall,
 * and red-packet grabbing.
 *
 * <p>All hooks are wrapped in try/catch and will not crash the target app if a
 * class or method is absent (e.g. due to obfuscation or DingTalk version changes).
 * Load these hooks after the DingTalk {@link ClassLoader} is available, i.e. from
 * {@code Main.onPackageReady}.</p>
 *
 * <p>Classes hooked here (location pipeline):</p>
 * <ul>
 *   <li>{@code com.alibaba.android.dingtalkbase.amap.GMapLocation} — DingTalk's
 *       {@code android.location.Location} subclass; overrides getLatitude/getLongitude.</li>
 *   <li>{@code com.alibaba.android.dingtalkbase.amap.LocationProxy} — caches and
 *       dispatches {@code AMapLocation} via {@code onLocationChanged}.</li>
 *   <li>{@code com.alibaba.wireless.security.aopsdk.replace.android.net.wifi.WifiManager}
 *       — AOP replacement class that DingTalk uses instead of the system WifiManager
 *       for getScanResults / startScan, bypassing system-level hooks.</li>
 * </ul>
 *
 * <p>Anti-recall: hooks the recall-event processor in
 * {@code com.alibaba.android.rimet.biz.session.convmsg.ConvMsgService} (primary) and
 * several fall-back class names used across different DingTalk 8.x builds.  When
 * enabled the recall notification is silently discarded so the original message
 * remains visible in the conversation.</p>
 *
 * <p>Red-packet grabbing: hooks the new-red-packet arrival callback in
 * {@code com.alibaba.android.rimet.biz.hbmanager.HongBaoManagerImpl} and
 * attempts to invoke the open/grab action automatically.</p>
 */
public class DingTalkDeepHookPlugin {

    private static final String TAG = "DingTalkDeepHook";

    /** One-shot flag: show hook-status Toast only the first time the attendance screen opens. */
    private static final AtomicBoolean sToastShown = new AtomicBoolean(false);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private DingTalkDeepHookPlugin() {
    }

    /**
     * Posts a hook-status Toast on the main thread the first time it is called.
     * Subsequent calls are ignored so the notification only appears once per
     * app session (i.e. the first time the attendance check-in screen is opened).
     *
     * @param ctx     application context inside the DingTalk process
     * @param active  true if location spoofing is currently enabled and configured
     */
    private static void showHookStatusToast(Context ctx, boolean active) {
        if (ctx == null) return;
        if (!sToastShown.compareAndSet(false, true)) return;
        String msg = active ? "✓ 虚拟定位 Hook 已激活" : "⚠ 虚拟定位 Hook 未启用，将使用真实位置";
        sMainHandler.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
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
        hookInitSecurityGuardRuntimeBase(module, classLoader);
        hookAntiRecall(module, classLoader);
        hookRedPacket(module, classLoader);
        module.log(Log.INFO, TAG, "DingTalk deep hooks installed");
    }

    // -----------------------------------------------------------------------
    // LightAppRuntimeReverseInterface (com.alibaba.dingtalk.runtimebase) —
    // second implementation of initSecurityGuard found in DingTalk 8.3.0.
    // Verified via APK decompilation artifact (class_names_8.3.0.txt).
    // Hooked directly via classLoader.loadClass() (not via versioned config),
    // so it runs alongside the DingDingPlugin config-based hook for the
    // com.alibaba.lightapp.runtime variant.
    // -----------------------------------------------------------------------

    private static void hookInitSecurityGuardRuntimeBase(XposedModule module, ClassLoader classLoader) {
        // Two candidate classes confirmed to contain initSecurityGuard in DingTalk 8.3.0:
        //   1. com.alibaba.lightapp.runtime.LightAppRuntimeReverseInterfaceImpl  (hooked via DingDingPlugin config)
        //   2. com.alibaba.dingtalk.runtimebase.LightAppRuntimeReverseInterface  (hooked here, class-loader path)
        String[] candidates = {
                "com.alibaba.dingtalk.runtimebase.LightAppRuntimeReverseInterface",
                "com.alibaba.dingtalk.runtimebase.LightAppRuntimeReverseInterface$$Impl",
        };
        for (String className : candidates) {
            try {
                Class<?> cls = classLoader.loadClass(className);
                for (Method m : cls.getDeclaredMethods()) {
                    if (!"initSecurityGuard".equals(m.getName())) continue;
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        Class<?> returnType = chain.getMethod().getReturnType();
                        if (returnType == boolean.class || returnType == Boolean.class) return false;
                        if (returnType == int.class    || returnType == Integer.class)  return 0;
                        if (returnType == long.class   || returnType == Long.class)     return 0L;
                        return null; // void or Object
                    });
                    module.log(Log.INFO, TAG,
                            "hookInitSecurityGuard installed: " + className);
                }
            } catch (ClassNotFoundException e) {
                // Class absent in this DingTalk version — skip silently.
            } catch (Throwable e) {
                module.log(Log.WARN, TAG,
                        "hookInitSecurityGuardRuntimeBase failed for " + className, e);
            }
        }
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
                    double baseLat = Double.parseDouble(val);
                    String lonVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                    String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    if (lonVal.isEmpty() || offVal.isEmpty()) return baseLat;
                    return SystemHookPlugin.getEffectiveCoords(
                            baseLat, Double.parseDouble(lonVal), Double.parseDouble(offVal))[0];
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
                    double baseLon = Double.parseDouble(val);
                    String latVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                    String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    if (latVal.isEmpty() || offVal.isEmpty()) return baseLon;
                    return SystemHookPlugin.getEffectiveCoords(
                            Double.parseDouble(latVal), baseLon, Double.parseDouble(offVal))[1];
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
                    double baseLat = Double.parseDouble(val);
                    String lonVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                    String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    if (lonVal.isEmpty() || offVal.isEmpty()) return chain.proceed(new Object[]{baseLat});
                    double effectiveLat = SystemHookPlugin.getEffectiveCoords(
                            baseLat, Double.parseDouble(lonVal), Double.parseDouble(offVal))[0];
                    return chain.proceed(new Object[]{effectiveLat});
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
                    double baseLon = Double.parseDouble(val);
                    String latVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                    String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    if (latVal.isEmpty() || offVal.isEmpty()) return chain.proceed(new Object[]{baseLon});
                    double effectiveLon = SystemHookPlugin.getEffectiveCoords(
                            Double.parseDouble(latVal), baseLon, Double.parseDouble(offVal))[1];
                    return chain.proceed(new Object[]{effectiveLon});
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

                // Apply lat/lon overrides with random offset.
                String latStr = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                String lonStr = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                String offStr = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                boolean patched = false;
                try {
                    double[] coords = null;
                    if (!latStr.isEmpty() && !lonStr.isEmpty() && !offStr.isEmpty()) {
                        coords = SystemHookPlugin.getEffectiveCoords(
                                Double.parseDouble(latStr), Double.parseDouble(lonStr),
                                Double.parseDouble(offStr));
                    }
                    if (!latStr.isEmpty()) {
                        double lat = (coords != null) ? coords[0] : Double.parseDouble(latStr);
                        setLat.invoke(aMapLocation, lat);
                        patched = true;
                    }
                    if (!lonStr.isEmpty()) {
                        double lon = (coords != null) ? coords[1] : Double.parseDouble(lonStr);
                        setLon.invoke(aMapLocation, lon);
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

            // startLocation() — show a Toast when DingTalk initiates location collection,
            // which happens when the user enters the attendance check-in screen (考勤打卡).
            // The Toast indicates whether location spoofing is active (hook success/failure).
            try {
                Method startLocation = clientCls.getMethod("startLocation");
                module.hook(startLocation).intercept(chain -> {
                    Object result = chain.proceed();
                    SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
                    boolean active = SystemHookPlugin.isEnabled(prefs)
                            && !SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE).isEmpty()
                            && !SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE).isEmpty();
                    showHookStatusToast(SystemHookPlugin.sAppContext, active);
                    return result;
                });
                module.log(Log.INFO, TAG, "hookAMapLocationClient#startLocation installed");
            } catch (Throwable e) {
                module.log(Log.WARN, TAG, "hookAMapLocationClient#startLocation failed", e);
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
                        double baseLat = Double.parseDouble(val);
                        String lonVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LONGITUDE);
                        String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                        if (lonVal.isEmpty() || offVal.isEmpty()) return baseLat;
                        return SystemHookPlugin.getEffectiveCoords(
                                baseLat, Double.parseDouble(lonVal), Double.parseDouble(offVal))[0];
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
                        double baseLon = Double.parseDouble(val);
                        String latVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LATITUDE);
                        String offVal = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                        if (latVal.isEmpty() || offVal.isEmpty()) return baseLon;
                        return SystemHookPlugin.getEffectiveCoords(
                                Double.parseDouble(latVal), baseLon, Double.parseDouble(offVal))[1];
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
                                        String offStr = SystemHookPlugin.getString(
                                                p, Constant.XFlag.LOCATION_OFFSET);
                                        try {
                                            double[] coords = null;
                                            if (!latStr.isEmpty() && !lonStr.isEmpty()
                                                    && !offStr.isEmpty()) {
                                                coords = SystemHookPlugin.getEffectiveCoords(
                                                        Double.parseDouble(latStr),
                                                        Double.parseDouble(lonStr),
                                                        Double.parseDouble(offStr));
                                            }
                                            if (!latStr.isEmpty()) {
                                                double latVal = (coords != null)
                                                        ? coords[0] : Double.parseDouble(latStr);
                                                setLatMethod.invoke(args[0], latVal);
                                            }
                                            if (!lonStr.isEmpty()) {
                                                double lonVal = (coords != null)
                                                        ? coords[1] : Double.parseDouble(lonStr);
                                                setLonMethod.invoke(args[0], lonVal);
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

    // -----------------------------------------------------------------------
    // Anti-recall (消息防撤回) — prevent server-pushed recall events from
    // removing messages from the local conversation view.
    // -----------------------------------------------------------------------

    /**
     * Hooks the recall-event processor(s) in DingTalk 8.x.
     *
     * <p>Multiple candidate class / method names are tried in sequence.  Each
     * attempt is individually wrapped in try/catch so a failure in one attempt
     * does not prevent the others from running.  The hooks intercept the method
     * <em>before</em> it executes and return {@code null} immediately (skipping
     * the original call) when anti-recall is enabled.</p>
     */
    private static void hookAntiRecall(XposedModule module, ClassLoader classLoader) {

        // Candidate 1 — ConvMsgService.onRevokeMsg (DingTalk 8.x primary path)
        tryHookAntiRecallMethod(module, classLoader,
                "com.alibaba.android.rimet.biz.session.convmsg.ConvMsgService",
                "onRevokeMsg");

        // Candidate 2 — alternate method name in the same class
        tryHookAntiRecallMethod(module, classLoader,
                "com.alibaba.android.rimet.biz.session.convmsg.ConvMsgService",
                "revokeMsg");

        // Candidate 3 — IMConversationReceiver (seen in some 7.x / 8.x builds)
        tryHookAntiRecallMethod(module, classLoader,
                "com.alibaba.android.rimet.biz.session.receiver.IMConversationReceiver",
                "onRevokeMsg");

        // Candidate 4 — message revoke processor (modular architecture)
        tryHookAntiRecallMethod(module, classLoader,
                "com.laiwang.android.message.lib.processor.MsgRevokeProcessor",
                "process");

        // Candidate 5 — revoke helper utility
        tryHookAntiRecallMethod(module, classLoader,
                "com.alibaba.android.rimet.biz.session.helper.MessageRevokeHelper",
                "revokeMessage");
    }

    /**
     * Attempts to hook every declared method with the given name on the given class.
     * Skips silently if the class or method cannot be found.
     */
    private static void tryHookAntiRecallMethod(XposedModule module, ClassLoader classLoader,
            String className, String methodName) {
        try {
            Class<?> cls = classLoader.loadClass(className);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    if (!SystemHookPlugin.getBoolFlag(module, Constant.XFlag.ENABLE_ANTI_RECALL)) {
                        return chain.proceed();
                    }
                    module.log(Log.INFO, TAG,
                            "AntiRecall: blocked " + chain.getMethod().getName()
                                    + " in " + className);
                    // Return null / 0 / false depending on return type to satisfy callers.
                    Class<?> returnType = chain.getMethod().getReturnType();
                    if (returnType == boolean.class || returnType == Boolean.class) return false;
                    if (returnType == int.class    || returnType == Integer.class)  return 0;
                    if (returnType == long.class   || returnType == Long.class)     return 0L;
                    return null; // void or Object
                });
                hooked = true;
            }
            if (hooked) {
                module.log(Log.INFO, TAG,
                        "hookAntiRecall installed: " + className + "#" + methodName);
            }
        } catch (ClassNotFoundException e) {
            // Class absent in this DingTalk version — skip silently.
        } catch (Throwable e) {
            module.log(Log.WARN, TAG,
                    "hookAntiRecall failed for " + className + "#" + methodName, e);
        }
    }

    // -----------------------------------------------------------------------
    // Red-packet grabbing (抢红包) — automatically claim red packets when
    // a new HongBao message arrives in a conversation.
    // -----------------------------------------------------------------------

    /**
     * Hooks the red-packet arrival callback(s) in DingTalk 8.x.
     *
     * <p>When enabled the hook calls the "open/grab" action on the HongBao
     * manager immediately after the arrival event fires, simulating the user
     * tapping the red packet envelope.</p>
     */
    private static void hookRedPacket(XposedModule module, ClassLoader classLoader) {

        // Candidate 1 — HongBaoManagerImpl (DingTalk 8.x primary path)
        tryHookRedPacketReceive(module, classLoader,
                "com.alibaba.android.rimet.biz.hbmanager.HongBaoManagerImpl",
                "onReceiveNewHb");

        // Candidate 2 — alternate arrival method name
        tryHookRedPacketReceive(module, classLoader,
                "com.alibaba.android.rimet.biz.hbmanager.HongBaoManagerImpl",
                "onReceiveHongBao");

        // Candidate 3 — HongBaoHelper (utility layer)
        tryHookRedPacketReceive(module, classLoader,
                "com.alibaba.android.dingtalkbase.multidexsupport.components.hb.HongBaoHelper",
                "onNewHongBao");

        // Candidate 4 — HongBaoComponent (component architecture)
        tryHookRedPacketReceive(module, classLoader,
                "com.alibaba.android.dingtalkbase.multidexsupport.components.hb.HongBaoComponent",
                "receiveHongBao");

        // Candidate 5 — push notification handler for red packets
        tryHookRedPacketReceive(module, classLoader,
                "com.alibaba.android.rimet.biz.hbmanager.HongBaoPushHandler",
                "onNewHongBao");
    }

    /**
     * Attempts to hook every declared method with the given name and registers an
     * after-hook that calls the first reachable "open/grab" method on {@code this}
     * (the manager instance) when red-packet grabbing is enabled.
     */
    private static void tryHookRedPacketReceive(XposedModule module, ClassLoader classLoader,
            String className, String methodName) {
        try {
            Class<?> cls = classLoader.loadClass(className);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    // Let the original method run first so the HongBao is registered.
                    Object result = chain.proceed();
                    if (!SystemHookPlugin.getBoolFlag(module, Constant.XFlag.ENABLE_RED_PACKET)) {
                        return result;
                    }
                    module.log(Log.INFO, TAG,
                            "RedPacket: auto-grabbing after " + chain.getMethod().getName()
                                    + " in " + className);
                    // Attempt to invoke grab/open on the same object instance.
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        invokeGrabMethod(module, thiz, chain.getArgs().toArray());
                    }
                    return result;
                });
                hooked = true;
            }
            if (hooked) {
                module.log(Log.INFO, TAG,
                        "hookRedPacket installed: " + className + "#" + methodName);
            }
        } catch (ClassNotFoundException e) {
            // Class absent — skip silently.
        } catch (Throwable e) {
            module.log(Log.WARN, TAG,
                    "hookRedPacket failed for " + className + "#" + methodName, e);
        }
    }

    /** Candidate "open/grab" method names tried in order on the HongBao manager instance. */
    private static final String[] GRAB_METHOD_NAMES =
            {"openHongBao", "grabHongBao", "receiveHb", "openHb", "grabHb"};

    /**
     * Tries to call a "grab/open" method on the HongBao manager instance.
     * Candidate method names are tried in order; the first one that succeeds wins.
     *
     * @param module the LibXposed module (for logging).
     * @param thiz   the HongBao manager instance returned by {@code chain.getThisObject()}.
     * @param args   the original method arguments (may carry HongBao ID / conversation info).
     */
    private static void invokeGrabMethod(XposedModule module, Object thiz, Object[] args) {
        // Cache declared methods once to avoid repeated O(n) reflection lookups.
        Method[] methods = thiz.getClass().getDeclaredMethods();
        for (String name : GRAB_METHOD_NAMES) {
            for (Method m : methods) {
                if (!name.equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    // Prefer a no-args call for simplicity; fall back to same-arity call
                    // using the original args if no no-args overload is present.
                    if (m.getParameterCount() == 0) {
                        m.invoke(thiz);
                    } else if (m.getParameterCount() == args.length) {
                        // IllegalArgumentException (type mismatch) is caught by the outer
                        // Throwable catch; it will be logged and the loop will try the next
                        // candidate rather than crashing.
                        m.invoke(thiz, args);
                    } else {
                        continue; // Arity mismatch — try next candidate.
                    }
                    module.log(Log.INFO, TAG, "RedPacket: invoked " + name + " successfully");
                    return;
                } catch (Throwable e) {
                    module.log(Log.WARN, TAG, "RedPacket: " + name + " invoke failed", e);
                }
            }
        }
    }
}
