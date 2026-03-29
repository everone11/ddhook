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

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.sky.xposed.rimet.BuildConfig;
import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.ConfigManager;
import com.sky.xposed.rimet.plugin.interfaces.XConfigManager;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * System-level Hook plugin that intercepts Android framework APIs for location,
 * WiFi and cell information. Works for any target app (DingTalk, Alipay, etc.)
 * without depending on app-specific class names or third-party map SDKs.
 *
 * <p>Configuration is read lazily from the module's own SharedPreferences via
 * {@code ActivityThread.currentApplication()} so this plugin can be installed
 * at package-ready time before any app Context is available.</p>
 */
public class SystemHookPlugin {

    private static final String TAG = "SystemHookPlugin";

    private final XposedInterface mXposedInterface;

    /** Lazily initialised once the app Context becomes available. */
    private volatile XConfigManager mConfigManager;

    public SystemHookPlugin(XposedInterface xposedInterface) {
        mXposedInterface = xposedInterface;
    }

    /**
     * Registers all system API hooks. Safe to call at package-ready time
     * before {@code Application.onCreate} has fired.
     */
    public void onHandleLoadPackage() {
        hookLocation();
        hookWifi();
        hookCell();
    }

    // -----------------------------------------------------------------------
    // Lazy config accessor
    // -----------------------------------------------------------------------

    private XConfigManager getConfigManager() {
        if (mConfigManager == null) {
            synchronized (this) {
                if (mConfigManager == null) {
                    try {
                        Method currentApp = Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication");
                        Application app = (Application) currentApp.invoke(null);
                        if (app != null) {
                            Context moduleCtx = app.createPackageContext(
                                    BuildConfig.APPLICATION_ID,
                                    Context.CONTEXT_IGNORE_SECURITY);
                            mConfigManager = new ConfigManager.Build(null)
                                    .setContext(moduleCtx)
                                    .setConfigName(Constant.Name.RIMET)
                                    .build();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to get module context for config", e);
                    }
                }
            }
        }
        return mConfigManager;
    }

    private boolean isVirtualLocationEnabled() {
        XConfigManager cm = getConfigManager();
        return cm != null && cm.getBoolean(Constant.XFlag.ENABLE_VIRTUAL_LOCATION, false);
    }

    // -----------------------------------------------------------------------
    // Hook: android.location.Location – getLatitude / getLongitude
    // -----------------------------------------------------------------------

    private void hookLocation() {
        try {
            Class<?> locationClass = Class.forName("android.location.Location");

            Method getLatitude = locationClass.getDeclaredMethod("getLatitude");
            hookMethod(getLatitude, chain -> {
                if (!isVirtualLocationEnabled()) return chain.proceed();
                XConfigManager cm = getConfigManager();
                if (cm == null) return chain.proceed();
                String lat = cm.getString(Constant.XFlag.VIRTUAL_LATITUDE, "");
                if (lat.isEmpty()) return chain.proceed();
                try {
                    return Double.parseDouble(lat);
                } catch (NumberFormatException ignored) {
                    Log.w(TAG, "Invalid latitude value: " + lat);
                    return chain.proceed();
                }
            });

            Method getLongitude = locationClass.getDeclaredMethod("getLongitude");
            hookMethod(getLongitude, chain -> {
                if (!isVirtualLocationEnabled()) return chain.proceed();
                XConfigManager cm = getConfigManager();
                if (cm == null) return chain.proceed();
                String lon = cm.getString(Constant.XFlag.VIRTUAL_LONGITUDE, "");
                if (lon.isEmpty()) return chain.proceed();
                try {
                    return Double.parseDouble(lon);
                } catch (NumberFormatException ignored) {
                    Log.w(TAG, "Invalid longitude value: " + lon);
                    return chain.proceed();
                }
            });

            Log.d(TAG, "Location hooks installed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook android.location.Location", e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: android.net.wifi.WifiInfo – getSSID / getBSSID
    // -----------------------------------------------------------------------

    private void hookWifi() {
        try {
            Class<?> wifiInfoClass = Class.forName("android.net.wifi.WifiInfo");

            Method getSSID = wifiInfoClass.getDeclaredMethod("getSSID");
            hookMethod(getSSID, chain -> {
                if (!isVirtualLocationEnabled()) return chain.proceed();
                XConfigManager cm = getConfigManager();
                if (cm == null) return chain.proceed();
                String ssid = cm.getString(Constant.XFlag.VIRTUAL_WIFI_SSID, "");
                if (ssid.isEmpty()) return chain.proceed();
                // Android wraps UTF-8 SSIDs in double-quotes; add them if not already present
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    return ssid;
                }
                return "\"" + ssid + "\"";
            });

            Method getBSSID = wifiInfoClass.getDeclaredMethod("getBSSID");
            hookMethod(getBSSID, chain -> {
                if (!isVirtualLocationEnabled()) return chain.proceed();
                XConfigManager cm = getConfigManager();
                if (cm == null) return chain.proceed();
                String bssid = cm.getString(Constant.XFlag.VIRTUAL_WIFI_BSSID, "");
                if (bssid.isEmpty()) return chain.proceed();
                return bssid;
            });

            Log.d(TAG, "WifiInfo hooks installed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook android.net.wifi.WifiInfo", e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: android.telephony.gsm.GsmCellLocation – getLac / getCid
    // NOTE: GsmCellLocation is deprecated since API 5 and not available on
    // Android 9+ (API 28). This hook covers legacy devices; apps targeting
    // Android 9+ use CellIdentityGsm from TelephonyManager.getAllCellInfo().
    // -----------------------------------------------------------------------

    private void hookCell() {
        try {
            Class<?> cellClass = Class.forName("android.telephony.gsm.GsmCellLocation");

            Method getLac = cellClass.getDeclaredMethod("getLac");
            hookMethod(getLac, chain -> {
                if (!isVirtualLocationEnabled()) return chain.proceed();
                XConfigManager cm = getConfigManager();
                if (cm == null) return chain.proceed();
                String lac = cm.getString(Constant.XFlag.VIRTUAL_CELL_LAC, "");
                if (lac.isEmpty()) return chain.proceed();
                try {
                    return Integer.parseInt(lac);
                } catch (NumberFormatException ignored) {
                    Log.w(TAG, "Invalid LAC value: " + lac);
                    return chain.proceed();
                }
            });

            Method getCid = cellClass.getDeclaredMethod("getCid");
            hookMethod(getCid, chain -> {
                if (!isVirtualLocationEnabled()) return chain.proceed();
                XConfigManager cm = getConfigManager();
                if (cm == null) return chain.proceed();
                String cid = cm.getString(Constant.XFlag.VIRTUAL_CELL_CID, "");
                if (cid.isEmpty()) return chain.proceed();
                try {
                    return Integer.parseInt(cid);
                } catch (NumberFormatException ignored) {
                    Log.w(TAG, "Invalid CID value: " + cid);
                    return chain.proceed();
                }
            });

            Log.d(TAG, "GsmCellLocation hooks installed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook android.telephony.gsm.GsmCellLocation", e);
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void hookMethod(Method method, XposedInterface.Hooker hooker) {
        try {
            method.setAccessible(true);
            mXposedInterface.hook(method).intercept(hooker);
        } catch (Exception e) {
            Log.e(TAG, "Failed to install hook for " + method.getName(), e);
        }
    }
}
