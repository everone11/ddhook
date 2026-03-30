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
import android.util.Log;

import com.sky.xposed.rimet.BuildConfig;
import com.sky.xposed.rimet.Constant;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * System-level Hook plugin that spoofs Location, WiFi and Cell information
 * for any in-scope application (DingTalk, Alipay, etc.).
 *
 * <p>Hooks Android framework APIs directly so the spoofing is version-agnostic
 * and works across any app without needing app-specific class-name mapping.</p>
 *
 * <p>Settings are read lazily from the module's own SharedPreferences via
 * {@code ActivityThread.currentApplication().createPackageContext()} so that
 * a Context is not needed at hook-registration time.</p>
 */
public class SystemHookPlugin {

    private static final String TAG = "SystemHookPlugin";

    private SystemHookPlugin() {
    }

    /**
     * Register all system-level hooks using the provided {@link XposedInterface}.
     * Safe to call from {@code onPackageReady} before any app Context is available.
     */
    public static void setup(XposedInterface xi) {
        hookLocation(xi);
        hookWifiInfo(xi);
        hookGsmCellLocation(xi);
    }

    // -----------------------------------------------------------------------
    // SharedPreferences helpers
    // -----------------------------------------------------------------------

    /**
     * Lazily obtains the module's SharedPreferences from within the hooked process.
     * Returns {@code null} if the preferences cannot be accessed (e.g. module not installed).
     */
    private static SharedPreferences getPrefs() {
        try {
            Context app = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (app == null) return null;
            Context moduleCtx = app.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            return moduleCtx.getSharedPreferences(Constant.Name.RIMET, Context.MODE_PRIVATE);
        } catch (Exception e) {
            Log.w(TAG, "getPrefs failed: " + e.getMessage());
            return null;
        }
    }

    private static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(
                Integer.toString(Constant.XFlag.ENABLE_LOCATION), false);
    }

    private static String getString(SharedPreferences prefs, int key) {
        return prefs != null ? prefs.getString(Integer.toString(key), "") : "";
    }

    // -----------------------------------------------------------------------
    // Location hooks
    // -----------------------------------------------------------------------

    private static void hookLocation(XposedInterface xi) {
        try {
            Class<?> cls = Class.forName("android.location.Location");

            Method getLatitude = cls.getMethod("getLatitude");
            xi.hook(getLatitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method getLongitude = cls.getMethod("getLongitude");
            xi.hook(getLongitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LONGITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

        } catch (Exception e) {
            Log.w(TAG, "hookLocation failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // WiFi hooks
    // -----------------------------------------------------------------------

    private static void hookWifiInfo(XposedInterface xi) {
        try {
            Class<?> cls = Class.forName("android.net.wifi.WifiInfo");

            Method getSSID = cls.getMethod("getSSID");
            xi.hook(getSSID).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                String ssid = getString(prefs, Constant.XFlag.WIFI_SSID);
                if (ssid.isEmpty()) return chain.proceed();
                // WifiInfo.getSSID() surrounds the name with double-quotes
                return "\"" + ssid + "\"";
            });

            Method getBSSID = cls.getMethod("getBSSID");
            xi.hook(getBSSID).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                // BSSID is not exposed in the UI; an empty value means "no override"
                String bssid = getString(prefs, Constant.XFlag.WIFI_BSSID);
                if (bssid.isEmpty()) return chain.proceed();
                return bssid;
            });

            Method getMacAddress = cls.getMethod("getMacAddress");
            xi.hook(getMacAddress).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                String mac = getString(prefs, Constant.XFlag.WIFI_MAC);
                if (mac.isEmpty()) return chain.proceed();
                // Validate MAC address format: XX:XX:XX:XX:XX:XX
                if (!mac.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) return chain.proceed();
                return mac;
            });

        } catch (Exception e) {
            Log.w(TAG, "hookWifiInfo failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Cell location hooks
    // -----------------------------------------------------------------------

    private static void hookGsmCellLocation(XposedInterface xi) {
        try {
            Class<?> cls = Class.forName("android.telephony.gsm.GsmCellLocation");

            Method getLac = cls.getMethod("getLac");
            xi.hook(getLac).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                // LAC is not exposed in the UI; an empty value means "no override"
                String val = getString(prefs, Constant.XFlag.CELL_LAC);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method getCid = cls.getMethod("getCid");
            xi.hook(getCid).intercept(chain -> {
                SharedPreferences prefs = getPrefs();
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.CELL_ID);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

        } catch (Exception e) {
            Log.w(TAG, "hookGsmCellLocation failed: " + e.getMessage());
        }
    }
}
