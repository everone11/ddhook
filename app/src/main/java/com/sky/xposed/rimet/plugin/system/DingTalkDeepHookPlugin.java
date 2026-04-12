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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * <p>Anti-recall: scans all declared methods of each class in {@code RECALL_CLASS_CANDIDATES}
 * and hooks every method whose name contains a recall/revoke keyword (e.g. "revoke",
 * "recall", "withdraw").  This means the hook automatically adapts when DingTalk renames
 * the method in a future version without requiring a manual config update.  If none of the
 * named candidate classes are found (heavily obfuscated builds), a full DEX-file scan of
 * DingTalk's own packages is performed as a fallback.</p>
 *
 * <p>Red-packet grabbing: scans all declared methods of each class in
 * {@code HONGBAO_CLASS_CANDIDATES} and hooks every method that looks like a "new red packet
 * arrived" callback (name contains a red-packet keyword AND an arrival keyword such as
 * "new", "receive", "push").  After the callback fires, the grab/open action is attempted
 * automatically via {@link #invokeGrabMethod}.  The same DEX-scan fallback applies when all
 * named candidates are absent.</p>
 */
public class DingTalkDeepHookPlugin {

    private static final String TAG = "DingTalkDeepHook";

    /** One-shot flag: show hook-status Toast only the first time the attendance screen opens. */
    private static final AtomicBoolean sToastShown = new AtomicBoolean(false);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    // -----------------------------------------------------------------------
    // Anti-recall: candidate classes + method-name patterns (消息防撤回)
    //
    // When DingTalk updates and renames a method inside a known class, the
    // pattern scanner will still find and hook it automatically — no manual
    // update required.  If a method moves to a brand-new class, add that class
    // to RECALL_CLASS_CANDIDATES.
    // -----------------------------------------------------------------------

    /** Candidate classes that may host a recall/revoke event handler, ordered by likelihood. */
    private static final String[] RECALL_CLASS_CANDIDATES = {
            // Primary path: DingTalk 8.x
            "com.alibaba.android.rimet.biz.session.convmsg.ConvMsgService",
            // Seen in some 7.x / 8.x builds
            "com.alibaba.android.rimet.biz.session.receiver.IMConversationReceiver",
            // Modular architecture (laiwang message layer)
            "com.laiwang.android.message.lib.processor.MsgRevokeProcessor",
            // Helper utility
            "com.alibaba.android.rimet.biz.session.helper.MessageRevokeHelper",
            // Additional candidates following DingTalk's naming conventions
            "com.alibaba.android.rimet.biz.session.service.ConversationService",
            "com.alibaba.android.rimet.biz.session.message.MessageEventHandler",
            "com.alibaba.android.rimet.biz.im.service.IMService",
            "com.alibaba.android.rimet.biz.chat.ChatMessageService",
            "com.laiwang.android.message.lib.processor.MessageProcessor",
    };

    /**
     * Method-name substrings that identify a recall/revoke event handler (case-insensitive).
     * Any declared method whose lowercased name contains one of these tokens will be hooked.
     */
    private static final String[] RECALL_METHOD_PATTERNS = {
            "revoke", "recall", "withdraw", "retract", "unsend",
    };

    // -----------------------------------------------------------------------
    // Red-packet: candidate classes + method-name patterns (抢红包)
    //
    // The arrival-callback scanner hooks any method that looks like a "new red
    // packet received" notification, so it continues to work even if DingTalk
    // renames the callback in a future build.
    // -----------------------------------------------------------------------

    /** Candidate classes that may host a red-packet arrival callback, ordered by likelihood. */
    private static final String[] HONGBAO_CLASS_CANDIDATES = {
            // Primary path: DingTalk 8.x
            "com.alibaba.android.rimet.biz.hbmanager.HongBaoManagerImpl",
            // Push notification handler
            "com.alibaba.android.rimet.biz.hbmanager.HongBaoPushHandler",
            // Utility layer (seen in some builds)
            "com.alibaba.android.dingtalkbase.multidexsupport.components.hb.HongBaoHelper",
            // Component architecture
            "com.alibaba.android.dingtalkbase.multidexsupport.components.hb.HongBaoComponent",
            // Additional candidates following DingTalk's naming conventions
            "com.alibaba.android.rimet.biz.hbmanager.HongBaoManager",
            "com.alibaba.android.rimet.biz.hbmanager.HongBaoService",
            "com.alibaba.android.rimet.biz.hbmanager.RedPacketManagerImpl",
            "com.alibaba.android.rimet.biz.hbmanager.HbManagerImpl",
            "com.laiwang.android.hongbao.HongBaoProcessor",
            // 7.5.0: decompiled APK shows package as "com.aliaba" (possible obfuscation artifact);
            // keeping both spellings so the runtime scanner covers either case.
            "com.alibaba.android.dingtalk.redpackets.base.RedPacketInterface", // Auto-added from APK analysis
            "com.aliaba.android.dingtalk.redpackets.base.RedPacketInterface",  // Auto-added from APK analysis (verbatim APK name)
    };

    // -----------------------------------------------------------------------
    // Virtual-location: candidate DingTalk-internal location proxy classes
    // (虚拟定位)
    //
    // When DingTalk renames or refactors its internal AMap wrapper classes
    // (e.g. GMapLocation, LocationProxy), the APK analysis workflow detects
    // the new class names and scripts/generate_version_config.py appends them
    // here.  hookLocationCandidates() applies coordinate and dispatch hooks
    // automatically for every class in this list.
    // -----------------------------------------------------------------------

    /**
     * Candidate DingTalk-internal location wrapper / proxy classes.
     *
     * <ul>
     *   <li>Classes that subclass {@code android.location.Location} and declare
     *       {@code getLatitude}/{@code getLongitude}/{@code setLatitude}/{@code setLongitude}
     *       — coordinate getter/setter hooks are applied.</li>
     *   <li>Classes with an {@code onLocationChanged(AMapLocation)} method
     *       — the AMapLocation arg is patched before being forwarded.</li>
     * </ul>
     */
    private static final String[] LOCATION_CLASS_CANDIDATES = {
            // DingTalk 8.x: GMapLocation (android.location.Location subclass)
            "com.alibaba.android.dingtalkbase.amap.GMapLocation",
            // DingTalk 8.x: LocationProxy (dispatches AMapLocation to DingTalk internals)
            "com.alibaba.android.dingtalkbase.amap.LocationProxy",
            "com.alibaba.android.dingtalkbase.amap.GMapLocationListener", // Auto-added from APK analysis
            "com.alibaba.android.rimet.model.DtLocation", // Auto-added from APK analysis
    };

    /**
     * Package prefixes used by the DEX-scan fallback when all named candidate class names
     * are absent (heavily obfuscated DingTalk builds).  Restricting the scan to DingTalk's
     * own packages avoids touching third-party SDK code that can never contain a recall or
     * red-packet handler, and keeps the enumeration fast.
     *
     * <p>DingTalk 7.5.0 ships its red-packet module under {@code com.aliaba.android.dingtalk.*}
     * (note the missing 'b' — this is the verbatim package name in the 7.5.0 APK, not a typo).
     * This prefix is therefore included here so the interface-based fallback can locate the
     * {@code RedPacketInterface} implementation classes.</p>
     */
    private static final String[] DEX_SCAN_PREFIXES = {
            "com.alibaba.android.rimet.",
            "com.laiwang.",
            "com.alibaba.android.dingtalkbase.",
            "com.aliaba.",   // DingTalk 7.5.0: red-packet module uses this spelling
    };

    /**
     * Fully-qualified names of the DingTalk red-packet callback interface that we look for when
     * no named {@link #HONGBAO_CLASS_CANDIDATES} are present (heavily obfuscated builds).
     * Two spellings are tried because DingTalk 7.5.0 uses {@code com.aliaba} (missing 'b') while
     * newer builds use the correct {@code com.alibaba} spelling.
     */
    private static final String[] REDPACKET_INTERFACES = {
            "com.alibaba.android.dingtalk.redpackets.base.RedPacketInterface",
            "com.aliaba.android.dingtalk.redpackets.base.RedPacketInterface",
    };

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
        hookLocationCandidates(module, classLoader);
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
                        Class<?> returnType = m.getReturnType();
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
    // Virtual-location candidates scanner — replaces the old hard-coded
    // hookGMapLocation + hookLocationProxy methods.
    // -----------------------------------------------------------------------

    /**
     * Hooks DingTalk-internal location wrapper/proxy classes listed in
     * {@link #LOCATION_CLASS_CANDIDATES}.
     *
     * <p>For each candidate class the scanner looks for:</p>
     * <ul>
     *   <li>{@code getLatitude}/{@code getLongitude} — returns the configured fake coordinate.</li>
     *   <li>{@code setLatitude}/{@code setLongitude} — replaces the argument so the cached
     *       value inside the object is also spoofed.</li>
     *   <li>{@code onLocationChanged(AMapLocation)} — patches the {@code AMapLocation} arg's
     *       coordinates before the DingTalk pipeline processes them.</li>
     * </ul>
     *
     * <p>New class names discovered during APK decompilation are appended to
     * {@link #LOCATION_CLASS_CANDIDATES} by {@code scripts/generate_version_config.py}.</p>
     */
    private static void hookLocationCandidates(XposedModule module, ClassLoader classLoader) {
        // Pre-load AMapLocation setters needed for onLocationChanged patching.
        Method setLatMethod = null;
        Method setLonMethod = null;
        Class<?> aMapLocationCls = null;
        try {
            aMapLocationCls = classLoader.loadClass("com.amap.api.location.AMapLocation");
            setLatMethod = aMapLocationCls.getMethod("setLatitude", double.class);
            setLonMethod = aMapLocationCls.getMethod("setLongitude", double.class);
        } catch (Throwable e) {
            module.log(Log.WARN, TAG,
                    "hookLocationCandidates: AMapLocation not available — onLocationChanged hooks skipped");
        }
        final Class<?> aMapCls = aMapLocationCls;
        final Method setLat = setLatMethod;
        final Method setLon = setLonMethod;

        for (String className : LOCATION_CLASS_CANDIDATES) {
            try {
                Class<?> cls = classLoader.loadClass(className);
                boolean hooked = false;
                for (Method m : cls.getDeclaredMethods()) {
                    m.setAccessible(true);
                    switch (m.getName()) {
                        case "getLatitude":
                            hookCoordGetter(module, m, className, true);
                            hooked = true;
                            break;
                        case "getLongitude":
                            hookCoordGetter(module, m, className, false);
                            hooked = true;
                            break;
                        case "setLatitude":
                            if (m.getParameterCount() == 1) {
                                hookCoordSetter(module, m, className, true);
                                hooked = true;
                            }
                            break;
                        case "setLongitude":
                            if (m.getParameterCount() == 1) {
                                hookCoordSetter(module, m, className, false);
                                hooked = true;
                            }
                            break;
                        case "onLocationChanged":
                            if (m.getParameterCount() == 1 && aMapCls != null
                                    && aMapCls.isAssignableFrom(m.getParameterTypes()[0])) {
                                hookOnLocationChangedMethod(module, m, className, setLat, setLon);
                                hooked = true;
                            }
                            break;
                    }
                }
                if (hooked) {
                    module.log(Log.INFO, TAG,
                            "hookLocationCandidates installed on: " + className);
                }
            } catch (ClassNotFoundException e) {
                // Class absent in this DingTalk version — skip silently.
            } catch (Throwable e) {
                module.log(Log.WARN, TAG,
                        "hookLocationCandidates error for " + className, e);
            }
        }
    }

    /** Hooks a {@code getLatitude()}/{@code getLongitude()} method to return the fake coordinate. */
    private static void hookCoordGetter(XposedModule module, Method m,
            String className, boolean isLat) {
        final int coordFlag = isLat ? Constant.XFlag.LATITUDE : Constant.XFlag.LONGITUDE;
        final int otherFlag = isLat ? Constant.XFlag.LONGITUDE : Constant.XFlag.LATITUDE;
        final String logTag = className + "#" + m.getName();
        module.hook(m).intercept(chain -> {
            SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
            if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
            String val = SystemHookPlugin.getString(prefs, coordFlag);
            if (val.isEmpty()) return chain.proceed();
            try {
                double base = Double.parseDouble(val);
                String other = SystemHookPlugin.getString(prefs, otherFlag);
                String off = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                SystemHookPlugin.logSpoofed(module, logTag);
                if (other.isEmpty() || off.isEmpty()) return base;
                double[] coords = SystemHookPlugin.getEffectiveCoords(
                        isLat ? base : Double.parseDouble(other),
                        isLat ? Double.parseDouble(other) : base,
                        Double.parseDouble(off));
                return isLat ? coords[0] : coords[1];
            } catch (NumberFormatException e) {
                return chain.proceed();
            }
        });
    }

    /**
     * Hooks a {@code setLatitude(double)}/{@code setLongitude(double)} method to replace
     * the argument with the configured fake coordinate (random offset applied if set).
     */
    private static void hookCoordSetter(XposedModule module, Method m,
            String className, boolean isLat) {
        final int coordFlag = isLat ? Constant.XFlag.LATITUDE : Constant.XFlag.LONGITUDE;
        final int otherFlag = isLat ? Constant.XFlag.LONGITUDE : Constant.XFlag.LATITUDE;
        module.hook(m).intercept(chain -> {
            SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
            if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
            String val = SystemHookPlugin.getString(prefs, coordFlag);
            if (val.isEmpty()) return chain.proceed();
            try {
                double base = Double.parseDouble(val);
                String other = SystemHookPlugin.getString(prefs, otherFlag);
                String off = SystemHookPlugin.getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                if (other.isEmpty() || off.isEmpty()) return chain.proceed(new Object[]{base});
                double[] coords = SystemHookPlugin.getEffectiveCoords(
                        isLat ? base : Double.parseDouble(other),
                        isLat ? Double.parseDouble(other) : base,
                        Double.parseDouble(off));
                return chain.proceed(new Object[]{isLat ? coords[0] : coords[1]});
            } catch (NumberFormatException e) {
                return chain.proceed();
            }
        });
    }

    /**
     * Hooks an {@code onLocationChanged(AMapLocation)} method to patch the
     * AMapLocation arg's coordinates before the DingTalk pipeline processes them.
     */
    private static void hookOnLocationChangedMethod(XposedModule module, Method m,
            String className, Method setLatMethod, Method setLonMethod) {
        final String logTag = className + "#onLocationChanged";
        module.hook(m).intercept(chain -> {
            SharedPreferences prefs = SystemHookPlugin.getPrefs(module);
            if (!SystemHookPlugin.isEnabled(prefs)) return chain.proceed();
            Object aMapLocation = chain.getArg(0);
            if (aMapLocation == null) return chain.proceed();
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
                if (!latStr.isEmpty() && setLatMethod != null) {
                    double lat = (coords != null) ? coords[0] : Double.parseDouble(latStr);
                    setLatMethod.invoke(aMapLocation, lat);
                    patched = true;
                }
                if (!lonStr.isEmpty() && setLonMethod != null) {
                    double lon = (coords != null) ? coords[1] : Double.parseDouble(lonStr);
                    setLonMethod.invoke(aMapLocation, lon);
                    patched = true;
                }
            } catch (Exception ignored) { }
            if (patched) SystemHookPlugin.logSpoofed(module, logTag);
            return chain.proceed();
        });
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
     * Hooks the recall-event processor(s) across all DingTalk versions.
     *
     * <p>For each class in {@link #RECALL_CLASS_CANDIDATES}, every declared method whose
     * name contains a recall/revoke keyword (see {@link #RECALL_METHOD_PATTERNS}) is
     * hooked.  This pattern-based scan automatically adapts when DingTalk renames the
     * method in a future build — no manual update required.</p>
     *
     * <p>If none of the named candidates are found (heavily obfuscated builds), a full
     * DEX-file scan of DingTalk's own packages ({@link #DEX_SCAN_PREFIXES}) is performed
     * as a fallback, covering any class with a matching method name.</p>
     */
    private static void hookAntiRecall(XposedModule module, ClassLoader classLoader) {
        boolean anyHooked = false;
        for (String className : RECALL_CLASS_CANDIDATES) {
            if (tryHookAntiRecallClass(module, classLoader, className)) {
                anyHooked = true;
            }
        }
        if (!anyHooked) {
            List<String> allClasses = enumerateDexClassNames(classLoader, DEX_SCAN_PREFIXES);
            for (String className : allClasses) {
                if (tryHookAntiRecallClass(module, classLoader, className)) {
                    anyHooked = true;
                }
            }
        }
        if (!anyHooked) {
            // Method names are also obfuscated — try annotation-based dispatch (laiwang/DingTalk
            // uses @ProcessorMethod-style annotations on revoke handlers).
            hookAntiRecallViaAnnotation(module, classLoader);
        }
    }

    /**
     * Scans all declared methods of the given class and hooks every method whose name
     * contains a recall/revoke keyword.  Skips silently if the class is not found.
     *
     * @return {@code true} if the class was found and at least one method was hooked.
     */
    private static boolean tryHookAntiRecallClass(XposedModule module, ClassLoader classLoader,
            String className) {
        try {
            Class<?> cls = classLoader.loadClass(className);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!matchesAny(m.getName(), RECALL_METHOD_PATTERNS)) continue;
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                m.setAccessible(true);
                final Class<?> hookReturnType = m.getReturnType();
                module.hook(m).intercept(chain -> {
                    if (!SystemHookPlugin.getBoolFlag(module, Constant.XFlag.ENABLE_ANTI_RECALL)) {
                        return chain.proceed();
                    }
                    // Return null / 0 / false depending on return type to satisfy callers.
                    Class<?> returnType = hookReturnType;
                    if (returnType == boolean.class || returnType == Boolean.class) return false;
                    if (returnType == int.class    || returnType == Integer.class)  return 0;
                    if (returnType == long.class   || returnType == Long.class)     return 0L;
                    return null; // void or Object
                });
                hooked = true;
            }
            if (hooked) {
                module.log(Log.INFO, TAG, "hookAntiRecall installed on: " + className);
            }
            return hooked;
        } catch (ClassNotFoundException e) {
            // Class absent in this DingTalk version — skip silently.
            return false;
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookAntiRecall error for " + className, e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Red-packet grabbing (抢红包) — automatically claim red packets when
    // a new HongBao message arrives in a conversation.
    // -----------------------------------------------------------------------

    /**
     * Hooks the red-packet arrival callback(s) across all DingTalk versions.
     *
     * <p>For each class in {@link #HONGBAO_CLASS_CANDIDATES}, every declared method that
     * looks like a "new red packet arrived" notification (see {@link #isHongBaoArrivalMethod})
     * is hooked.  This pattern-based scan automatically adapts when DingTalk renames the
     * callback in a future build — no manual update required.</p>
     *
     * <p>When enabled the hook calls the "open/grab" action on the HongBao manager
     * immediately after the arrival event fires, simulating the user tapping the
     * red packet envelope.</p>
     *
     * <p>If none of the named candidates are found (heavily obfuscated builds), a full
     * DEX-file scan of DingTalk's own packages ({@link #DEX_SCAN_PREFIXES}) is performed
     * as a fallback.</p>
     */
    private static void hookRedPacket(XposedModule module, ClassLoader classLoader) {
        boolean anyHooked = false;
        for (String className : HONGBAO_CLASS_CANDIDATES) {
            if (tryHookRedPacketClass(module, classLoader, className)) {
                anyHooked = true;
            }
        }
        if (!anyHooked) {
            List<String> allClasses = enumerateDexClassNames(classLoader, DEX_SCAN_PREFIXES);
            for (String className : allClasses) {
                if (tryHookRedPacketClass(module, classLoader, className)) {
                    anyHooked = true;
                }
            }
        }
        if (!anyHooked) {
            // Method names are also obfuscated — find all classes implementing the known
            // RedPacketInterface and hook ALL their instance methods (7.5.0 fallback).
            hookRedPacketViaInterfaceImpl(module, classLoader);
        }
    }

    /**
     * Scans all declared methods of the given class and hooks every method that looks
     * like a "new red packet arrived" event callback.  Skips silently if the class is
     * not found.
     *
     * @return {@code true} if the class was found and at least one method was hooked.
     */
    private static boolean tryHookRedPacketClass(XposedModule module, ClassLoader classLoader,
            String className) {
        try {
            Class<?> cls = classLoader.loadClass(className);
            boolean hooked = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (!isHongBaoArrivalMethod(m.getName())) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    // Let the original method run first so the HongBao is registered.
                    Object result = chain.proceed();
                    if (!SystemHookPlugin.getBoolFlag(module, Constant.XFlag.ENABLE_RED_PACKET)) {
                        return result;
                    }
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
                module.log(Log.INFO, TAG, "hookRedPacket installed on: " + className);
            }
            return hooked;
        } catch (ClassNotFoundException e) {
            // Class absent — skip silently.
            return false;
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookRedPacket error for " + className, e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Fallback 3: interface-implementor scan (red-packet, 7.5.0 obfuscated)
    // -----------------------------------------------------------------------

    /**
     * Last-resort red-packet hook for heavily obfuscated DingTalk builds (e.g. 7.5.0).
     *
     * <p>When {@link #HONGBAO_CLASS_CANDIDATES} all fail and the DEX method-name scan
     * also finds nothing (because method names are obfuscated), we look for all classes
     * that <em>implement</em> one of the known {@link #REDPACKET_INTERFACES}.  Every
     * non-synthetic instance method in each implementor is hooked — so the arrival
     * callback fires regardless of its obfuscated name.</p>
     *
     * <p>After the intercepted method returns we call {@link #invokeGrabMethod} to
     * attempt the auto-grab, same as the normal path.</p>
     */
    private static void hookRedPacketViaInterfaceImpl(XposedModule module,
            ClassLoader classLoader) {
        for (String ifaceName : REDPACKET_INTERFACES) {
            Class<?> iface;
            try {
                iface = classLoader.loadClass(ifaceName);
            } catch (ClassNotFoundException e) {
                continue; // Not present in this DingTalk build.
            }

            List<String> candidates = enumerateDexClassNames(classLoader, DEX_SCAN_PREFIXES);
            int hookCount = 0;
            for (String className : candidates) {
                try {
                    Class<?> cls = classLoader.loadClass(className);
                    // Skip the interface itself, abstract classes, and non-implementors.
                    if (cls.isInterface() || !iface.isAssignableFrom(cls)) continue;
                    if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) continue;

                    for (Method m : cls.getDeclaredMethods()) {
                        // Skip bridge/synthetic methods (compiler-generated forwarding stubs).
                        if (m.isBridge() || m.isSynthetic()) continue;
                        // Skip static methods — callbacks are always instance methods.
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        m.setAccessible(true);
                        module.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            if (!SystemHookPlugin.getBoolFlag(
                                    module, Constant.XFlag.ENABLE_RED_PACKET)) {
                                return result;
                            }
                            Object thiz = chain.getThisObject();
                            if (thiz != null) {
                                invokeGrabMethod(module, thiz, chain.getArgs().toArray());
                            }
                            return result;
                        });
                        hookCount++;
                    }
                } catch (Throwable ignored) {
                    // Class not loadable or hook failed — skip.
                }
            }
            if (hookCount > 0) {
                module.log(Log.INFO, TAG,
                        "hookRedPacketViaInterfaceImpl: installed " + hookCount
                        + " hooks via " + ifaceName);
                return; // One interface is enough.
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fallback 3: annotation-based dispatch scan (anti-recall, 7.5.0 obfuscated)
    // -----------------------------------------------------------------------

    /**
     * Last-resort anti-recall hook for heavily obfuscated DingTalk builds (e.g. 7.5.0).
     *
     * <p>DingTalk's laiwang message layer dispatches incoming messages to annotated handler
     * methods.  Even when class and method names are obfuscated by ProGuard, the
     * <em>annotation types</em> and their attribute values are often retained (they are
     * used by the framework via reflection).  This method scans all DEX classes and looks
     * for methods carrying an annotation whose type name (simple or FQ) contains a
     * message-handler keyword <em>and</em> whose string attribute value contains a
     * recall/revoke keyword.  Those methods are hooked to block the revoke action.</p>
     *
     * <p>If no annotation-matched method is found, the method also tries a broader
     * parameter-type scan: any single-parameter void method whose parameter's simple
     * class name contains a recall/revoke keyword (covers partially-obfuscated builds
     * where the message-object type name survived ProGuard).</p>
     */
    private static void hookAntiRecallViaAnnotation(XposedModule module,
            ClassLoader classLoader) {
        List<String> candidates = enumerateDexClassNames(classLoader, DEX_SCAN_PREFIXES);
        int annotationHooks = 0;
        int signatureHooks  = 0;

        for (String className : candidates) {
            try {
                Class<?> cls = classLoader.loadClass(className);
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;

                    // ── Annotation-based detection ──────────────────────────────────────
                    boolean annotationMatch = false;
                    for (java.lang.annotation.Annotation ann : m.getDeclaredAnnotations()) {
                        String annType = ann.annotationType().getName().toLowerCase(Locale.US);
                        // Look for annotations whose type name suggests message/processor dispatch.
                        boolean isHandlerAnnotation = annType.contains("processor")
                                || annType.contains("handler")
                                || annType.contains("msghandler")
                                || annType.contains("subscribe")
                                || annType.contains("receiver");
                        if (!isHandlerAnnotation) continue;

                        // Check if any String attribute of the annotation contains a revoke keyword.
                        try {
                            for (java.lang.reflect.Method attr
                                    : ann.annotationType().getDeclaredMethods()) {
                                Object val = attr.invoke(ann);
                                if (val instanceof String) {
                                    String s = ((String) val).toLowerCase(Locale.US);
                                    if (s.contains("revoke") || s.contains("recall")
                                            || s.contains("withdraw") || s.contains("retract")
                                            || s.contains("unsend")) {
                                        annotationMatch = true;
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        if (annotationMatch) break;
                    }

                    // ── Parameter-type signature detection ─────────────────────────────
                    // Fallback: void/boolean method with ≥1 param whose simple type name
                    // contains a recall/revoke keyword (partially-obfuscated builds only).
                    boolean signatureMatch = false;
                    if (!annotationMatch) {
                        Class<?> ret = m.getReturnType();
                        boolean voidOrBool = ret == void.class || ret == boolean.class;
                        if (voidOrBool && m.getParameterCount() >= 1) {
                            for (Class<?> p : m.getParameterTypes()) {
                                String pn = p.getSimpleName().toLowerCase(Locale.US);
                                if (pn.contains("revoke") || pn.contains("recall")
                                        || pn.contains("withdraw")) {
                                    signatureMatch = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (!annotationMatch && !signatureMatch) continue;

                    m.setAccessible(true);
                    final Class<?> hookReturnType = m.getReturnType();
                    module.hook(m).intercept(chain -> {
                        if (!SystemHookPlugin.getBoolFlag(
                                module, Constant.XFlag.ENABLE_ANTI_RECALL)) {
                            return chain.proceed();
                        }
                        Class<?> returnType = hookReturnType;
                        if (returnType == boolean.class || returnType == Boolean.class)
                            return false;
                        if (returnType == int.class || returnType == Integer.class) return 0;
                        if (returnType == long.class || returnType == Long.class) return 0L;
                        return null;
                    });
                    if (annotationMatch) annotationHooks++;
                    else signatureHooks++;
                }
            } catch (Throwable ignored) {
                // Class not loadable — skip.
            }
        }
        if (annotationHooks + signatureHooks > 0) {
            module.log(Log.INFO, TAG,
                    "hookAntiRecallViaAnnotation: installed " + annotationHooks
                    + " annotation hooks, " + signatureHooks + " signature hooks");
        }
    }

    // -----------------------------------------------------------------------
    // Shared pattern-matching helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code name} (case-insensitive) contains any of the given tokens.
     */
    private static boolean matchesAny(String name, String[] tokens) {
        String lower = name.toLowerCase(Locale.US);
        for (String token : tokens) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    /**
     * Heuristic: returns {@code true} when a method name looks like a "new red packet
     * arrived" callback.
     *
     * <p>Rules (evaluated on the lower-cased method name):</p>
     * <ul>
     *   <li>Contains a red-packet keyword ({@code hongbao}, {@code redpacket}, …) AND
     *       an arrival keyword ({@code new}, {@code receiv}, {@code push}, {@code arriv},
     *       {@code notif}).</li>
     *   <li>OR contains the short keyword {@code hb} AND an arrival keyword.</li>
     *   <li>OR starts with {@code "on"} and ends with {@code "hb"} (e.g. {@code onHb}).</li>
     * </ul>
     *
     * <p>This deliberately excludes "open/grab" methods ({@code openHongBao}, {@code grabHb},
     * etc.) because they contain none of the arrival keywords.</p>
     */
    private static boolean isHongBaoArrivalMethod(String name) {
        String lower = name.toLowerCase(Locale.US);
        boolean hasHbWord   = lower.contains("hongbao") || lower.contains("redpacket")
                           || lower.contains("red_packet");
        boolean hasHbShort  = lower.contains("hb");
        boolean hasArrival  = lower.contains("new")    || lower.contains("receiv")
                           || lower.contains("push")   || lower.contains("arriv")
                           || lower.contains("notif");
        // Short-form callbacks: "onHb" — starts with "on", ends with "hb"
        boolean isOnHbShort = lower.startsWith("on") && lower.endsWith("hb");
        return (hasHbWord && hasArrival) || (hasHbShort && hasArrival) || isOnHbShort;
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
                    return;
                } catch (Throwable e) {
                    module.log(Log.WARN, TAG, "RedPacket: " + name + " invoke failed", e);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // DEX class enumeration — fallback for heavily obfuscated DingTalk builds
    // -----------------------------------------------------------------------

    /**
     * Enumerates all class names from the DingTalk DEX files whose package starts with
     * any of the given {@code prefixes}.  Returns an empty list if enumeration fails.
     *
     * <p>Implementation: reflects into
     * {@code BaseDexClassLoader → pathList (DexPathList) → dexElements[] → dexFile (DexFile)}
     * and calls {@code DexFile.entries()} on each element's backing DEX file.  Works on
     * API 26–35; falls back gracefully to an empty list if the internal structure has
     * changed in a future Android release.</p>
     *
     * <p>Only class names that start with at least one prefix in {@code prefixes} are
     * included, which keeps the result set small and avoids touching third-party SDK code.</p>
     *
     * @param classLoader the DingTalk process class loader (must be a BaseDexClassLoader).
     * @param prefixes    package prefixes to include (e.g. {@code "com.alibaba.android.rimet."}).
     * @return matching class names, possibly empty if enumeration failed or nothing matched.
     */
    @SuppressWarnings({"deprecation", "JavaReflectionMemberAccess"})
    static List<String> enumerateDexClassNames(ClassLoader classLoader, String[] prefixes) {
        // Use a LinkedHashSet to deduplicate class names that appear in multiple DEX files
        // (e.g. when DingTalk loads additional split APKs or feature modules).
        Set<String> seen = new LinkedHashSet<>();
        try {
            // BaseDexClassLoader.pathList → DexPathList
            Class<?> baseDexCls = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListField = baseDexCls.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) return new ArrayList<>(seen);

            // DexPathList.dexElements → Element[]
            Class<?> dexPathListCls = Class.forName("dalvik.system.DexPathList");
            Field dexElementsField = dexPathListCls.getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] elements = (Object[]) dexElementsField.get(pathList);
            if (elements == null) return new ArrayList<>(seen);

            for (Object element : elements) {
                // Element.dexFile → DexFile (null for native-lib elements)
                Field dexFileField;
                try {
                    dexFileField = element.getClass().getDeclaredField("dexFile");
                } catch (NoSuchFieldException e) {
                    continue; // Native-library element — skip.
                }
                dexFileField.setAccessible(true);
                Object dexFile = dexFileField.get(element);
                if (dexFile == null) continue;

                // DexFile.entries() → Enumeration<String> of class names
                Method entriesMethod = dexFile.getClass().getMethod("entries");
                @SuppressWarnings("unchecked")
                Enumeration<String> entries =
                        (Enumeration<String>) entriesMethod.invoke(dexFile);
                if (entries == null) continue;

                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    for (String prefix : prefixes) {
                        if (name.startsWith(prefix)) {
                            seen.add(name);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            // Enumeration failed (internal Android structure changed or restricted by
            // the runtime).  Log at WARN level so developers can diagnose the failure
            // when running the module on future Android versions.
            android.util.Log.w(TAG, "enumerateDexClassNames failed — DEX scan unavailable", e);
        }
        return new ArrayList<>(seen);
    }

    // -----------------------------------------------------------------------
    // Adaptation probe — used by the settings UI to display hook status.
    // -----------------------------------------------------------------------

    /**
     * Probes which anti-recall and red-packet hooks will be active for the
     * currently installed DingTalk version and returns a human-readable
     * summary suitable for display in the settings UI.
     *
     * <p>This method does <em>not</em> install any hooks — it is purely
     * informational and safe to call from a settings dialog.</p>
     *
     * @param classLoader the DingTalk process class loader.
     * @return list of result lines (one per candidate class), each prefixed
     *         with "✓" (class found, at least one matching method found),
     *         "△" (class found but no matching method found), or
     *         "✗" (class not present in this DingTalk version).
     */
    public static List<String> probeAdaptation(ClassLoader classLoader) {
        List<String> results = new ArrayList<>();

        results.add("── 虚拟定位 (Virtual Location) ──");
        for (String className : LOCATION_CLASS_CANDIDATES) {
            String simpleClass = simpleClassName(className);
            try {
                Class<?> cls = classLoader.loadClass(className);
                List<String> found = new ArrayList<>();
                for (Method m : cls.getDeclaredMethods()) {
                    switch (m.getName()) {
                        case "getLatitude": case "getLongitude":
                        case "setLatitude": case "setLongitude":
                        case "onLocationChanged":
                            found.add(m.getName());
                            break;
                    }
                }
                if (found.isEmpty()) {
                    results.add("△ " + simpleClass + " (无坐标/回调方法)");
                } else {
                    results.add("✓ " + simpleClass + ": " + String.join(", ", found));
                }
            } catch (ClassNotFoundException e) {
                results.add("✗ " + simpleClass);
            }
        }

        results.add("");
        results.add("── 消息防撤回 (Anti-Recall) ──");
        boolean anyRecallFound = false;
        for (String className : RECALL_CLASS_CANDIDATES) {
            String line = probeClass(classLoader, className, RECALL_METHOD_PATTERNS,
                    "recall/revoke");
            results.add(line);
            if (line.startsWith("✓") || line.startsWith("△")) anyRecallFound = true;
        }
        if (!anyRecallFound) {
            // All known candidate class names were absent; falling back to DEX-wide scan.
            results.add("△ (已知类名均未找到, 正在使用 DEX 扫描回退...)");
            List<String> dexClasses = enumerateDexClassNames(classLoader, DEX_SCAN_PREFIXES);
            int dexHits = 0;
            for (String className : dexClasses) {
                String line = probeClass(classLoader, className, RECALL_METHOD_PATTERNS,
                        "recall/revoke");
                if (line.startsWith("✓")) {
                    results.add(line);
                    dexHits++;
                }
            }
            if (dexHits == 0) {
                // DEX scan found no revoke/recall methods — method names may also be obfuscated.
                results.add("✗ DEX 扫描: 未找到 revoke/recall 方法 (方法名可能也已混淆)");
                // Try annotation/signature detection (informational only in probe).
                // Annotation-based (laiwang @ProcessorMethod / @Subscribe dispatch)
                int annHits = 0;
                int sigHits = 0;
                for (String className : dexClasses) {
                    try {
                        Class<?> cls = classLoader.loadClass(className);
                        for (Method m : cls.getDeclaredMethods()) {
                            if (m.isBridge() || m.isSynthetic()) continue;
                            boolean annMatch = false;
                            for (java.lang.annotation.Annotation ann : m.getDeclaredAnnotations()) {
                                String annType = ann.annotationType().getName().toLowerCase(Locale.US);
                                if (annType.contains("processor") || annType.contains("handler")
                                        || annType.contains("subscribe") || annType.contains("receiver")) {
                                    try {
                                        for (java.lang.reflect.Method attr
                                                : ann.annotationType().getDeclaredMethods()) {
                                            Object val = attr.invoke(ann);
                                            if (val instanceof String) {
                                                String s = ((String) val).toLowerCase(Locale.US);
                                                if (s.contains("revoke") || s.contains("recall")
                                                        || s.contains("withdraw")) {
                                                    annMatch = true; break;
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                if (annMatch) break;
                            }
                            boolean sigMatch = false;
                            if (!annMatch) {
                                Class<?> ret = m.getReturnType();
                                if ((ret == void.class || ret == boolean.class)
                                        && m.getParameterCount() >= 1) {
                                    for (Class<?> p : m.getParameterTypes()) {
                                        String pn = p.getSimpleName().toLowerCase(Locale.US);
                                        if (pn.contains("revoke") || pn.contains("recall")
                                                || pn.contains("withdraw")) {
                                            sigMatch = true; break;
                                        }
                                    }
                                }
                            }
                            if (annMatch) {
                                results.add("✓ (ann) " + simpleClassName(className)
                                        + "." + m.getName() + "()");
                                annHits++;
                            } else if (sigMatch) {
                                results.add("✓ (sig) " + simpleClassName(className)
                                        + "." + m.getName() + "("
                                        + m.getParameterTypes()[0].getSimpleName() + ")");
                                sigHits++;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (annHits == 0 && sigHits == 0) {
                    results.add("✗ 注解/签名扫描: 未找到 (方法名和参数类型均已混淆) — 需重新分析 7.5.0 APK");
                    // No annotation/signature match — method names and param types are obfuscated.
                }
            }
        }

        results.add("");
        results.add("── 抢红包 (Red-Packet) ──");
        boolean anyHbFound = false;
        for (String className : HONGBAO_CLASS_CANDIDATES) {
            List<String> matched = findMatchingMethodNames(classLoader, className,
                    DingTalkDeepHookPlugin::isHongBaoArrivalMethod);
            String simpleClass = simpleClassName(className);
            if (matched == null) {
                results.add("✗ " + simpleClass);
            } else if (matched.isEmpty()) {
                results.add("△ " + simpleClass + " (无匹配方法)");
            } else {
                results.add("✓ " + simpleClass + ": " + String.join(", ", matched));
                anyHbFound = true;
            }
        }
        if (!anyHbFound) {
            // All known candidate class names were absent; falling back to DEX-wide scan.
            results.add("△ (已知类名均未找到, 正在使用 DEX 扫描回退...)");
            List<String> dexClasses = enumerateDexClassNames(classLoader, DEX_SCAN_PREFIXES);
            int dexHits = 0;
            for (String className : dexClasses) {
                List<String> matched = findMatchingMethodNames(classLoader, className,
                        DingTalkDeepHookPlugin::isHongBaoArrivalMethod);
                if (matched != null && !matched.isEmpty()) {
                    results.add("✓ " + simpleClassName(className)
                            + ": " + String.join(", ", matched));
                    dexHits++;
                }
            }
            if (dexHits == 0) {
                // DEX scan found no red-packet arrival methods — method names may also be obfuscated.
                results.add("✗ DEX 扫描: 未找到红包到达方法 (方法名可能也已混淆)");
                // Try the interface-implementor approach (informational probe).
                results.add("── RedPacketInterface 实现类扫描 ──");
                boolean ifaceHit = false;
                for (String ifaceName : REDPACKET_INTERFACES) {
                    try {
                        Class<?> iface = classLoader.loadClass(ifaceName);
                        String simpleIface = simpleClassName(ifaceName);
                        int implCount = 0;
                        int methodCount = 0;
                        for (String className : dexClasses) {
                            try {
                                Class<?> cls = classLoader.loadClass(className);
                                if (cls.isInterface() || !iface.isAssignableFrom(cls)) continue;
                                if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) continue;
                                implCount++;
                                for (Method m : cls.getDeclaredMethods()) {
                                    if (!m.isBridge() && !m.isSynthetic()
                                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                                        methodCount++;
                                    }
                                }
                                results.add("✓ (impl) " + simpleClassName(className)
                                        + " implements " + simpleIface);
                                ifaceHit = true;
                            } catch (Throwable ignored) {}
                        }
                        if (implCount > 0) {
                            results.add("  → 找到 " + implCount + " 个实现类, " + methodCount + " 个可拦截方法");
                            // Found implementations → announce they will be hooked.
                        } else {
                            results.add("△ " + simpleIface + " 接口未找到实现类");
                        }
                    } catch (ClassNotFoundException e) {
                        results.add("✗ " + simpleClassName(ifaceName) + " (接口未找到)");
                    }
                }
                if (!ifaceHit) {
                    results.add("✗ 接口实现扫描: 未找到实现类 — 需重新分析 7.5.0 APK 获取混淆类名");
                    // No implementations found — need APK re-analysis to get obfuscated names.
                }
            }
        }

        results.add("");
        results.add("── 抢红包 open/grab 方法 ──");
        for (String className : HONGBAO_CLASS_CANDIDATES) {
            List<String> grabMatches = new ArrayList<>();
            try {
                Class<?> cls = classLoader.loadClass(className);
                for (Method m : cls.getDeclaredMethods()) {
                    for (String g : GRAB_METHOD_NAMES) {
                        if (g.equals(m.getName())) {
                            grabMatches.add(m.getName());
                            break;
                        }
                    }
                }
                if (!grabMatches.isEmpty()) {
                    results.add("✓ " + simpleClassName(className)
                            + ": " + String.join(", ", grabMatches));
                }
            } catch (ClassNotFoundException ignored) {
                // class absent — skip
            }
        }

        return results;
    }

    /** Helper: probe a single class for recall/revoke pattern matches. */
    private static String probeClass(ClassLoader classLoader, String className,
            String[] patterns, String label) {
        String simpleClass = simpleClassName(className);
        List<String> matched = findMatchingMethodNames(classLoader, className,
                name -> matchesAny(name, patterns));
        if (matched == null) return "✗ " + simpleClass;
        if (matched.isEmpty()) return "△ " + simpleClass + " (无匹配方法)";
        return "✓ " + simpleClass + ": " + String.join(", ", matched);
    }

    /**
     * Returns the simple (unqualified) class name from a fully-qualified class name.
     * If the name contains no '.', the full name is returned unchanged.
     */
    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    /**
     * Returns the names of all declared methods in {@code className} that pass
     * the given predicate, or {@code null} if the class is not found.
     */
    private static List<String> findMatchingMethodNames(ClassLoader classLoader,
            String className, java.util.function.Predicate<String> predicate) {
        try {
            Class<?> cls = classLoader.loadClass(className);
            List<String> names = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                if (predicate.test(m.getName())) {
                    names.add(m.getName());
                }
            }
            return names;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
