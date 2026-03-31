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
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import com.sky.xposed.rimet.Constant;

import java.lang.reflect.Method;
import java.util.Collections;

import io.github.libxposed.api.XposedModule;

/**
 * System-level Hook plugin that spoofs Location, WiFi and Cell information
 * for any in-scope application (DingTalk, Alipay, etc.).
 *
 * <p>Hooks Android framework APIs directly so the spoofing is version-agnostic
 * and works across any app without needing app-specific class-name mapping.</p>
 *
 * <p>Settings are read via {@link XposedModule#getRemotePreferences(String)} which
 * uses LibXposed's cross-process IPC (backed by {@code XposedProvider}) to safely
 * read the module UI's SharedPreferences from inside the hooked-app process.
 * A lightweight in-memory cache (2-second TTL) prevents IPC overhead on every
 * hooked call.</p>
 */
public class SystemHookPlugin {

    private static final String TAG = "SystemHookPlugin";

    // -----------------------------------------------------------------------
    // Preference cache — refreshed at most once every PREFS_CACHE_TTL_MS.
    // Volatile so changes are visible across threads without full synchronisation.
    // -----------------------------------------------------------------------
    private static volatile SharedPreferences sPrefsCache = null;
    private static volatile long sPrefsLastRefreshMs = 0L;
    private static final long PREFS_CACHE_TTL_MS = 2_000L;

    // Rate-limited spoof-log to avoid logcat flooding on high-frequency hooks.
    private static volatile long sLastSpoofLogMs = 0L;
    private static final long LOG_INTERVAL_MS = 5_000L;

    private SystemHookPlugin() {
    }

    /**
     * Register all system-level hooks using the provided {@link XposedModule}.
     * Safe to call from {@code onPackageReady} before any app Context is available.
     */
    public static void setup(XposedModule module) {
        hookLocation(module);
        hookAMapLocation(module);
        hookWifiInfo(module);
        hookWifiScanResults(module);
        hookGsmCellLocation(module);
        hookAllCellInfo(module);
        Log.i(TAG, "System hooks installed");
    }

    // -----------------------------------------------------------------------
    // SharedPreferences helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the module's SharedPreferences, refreshed via LibXposed IPC at most
     * once per {@link #PREFS_CACHE_TTL_MS} to avoid per-call IPC overhead.
     */
    static SharedPreferences getPrefs(XposedModule module) {
        long now = System.currentTimeMillis();
        if (sPrefsCache == null || (now - sPrefsLastRefreshMs) > PREFS_CACHE_TTL_MS) {
            sPrefsCache = module.getRemotePreferences(Constant.Name.RIMET);
            sPrefsLastRefreshMs = now;
        }
        return sPrefsCache;
    }

    static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(
                Integer.toString(Constant.XFlag.ENABLE_LOCATION), false);
    }

    static String getString(SharedPreferences prefs, int key) {
        return prefs != null ? prefs.getString(Integer.toString(key), "") : "";
    }

    /** Rate-limited info log emitted when a spoof value is actually returned. */
    static void logSpoofed(String field) {
        long now = System.currentTimeMillis();
        if (now - sLastSpoofLogMs > LOG_INTERVAL_MS) {
            sLastSpoofLogMs = now;
            Log.i(TAG, "Spoofing " + field);
        }
    }

    // -----------------------------------------------------------------------
    // Location hooks
    // -----------------------------------------------------------------------

    private static void hookLocation(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.location.Location");

            Method getLatitude = cls.getMethod("getLatitude");
            module.hook(getLatitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    logSpoofed("android.location.Location#getLatitude");
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method getLongitude = cls.getMethod("getLongitude");
            module.hook(getLongitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LONGITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    logSpoofed("android.location.Location#getLongitude");
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Log.i(TAG, "hookLocation installed");
        } catch (Exception e) {
            Log.w(TAG, "hookLocation failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // WiFi hooks
    // -----------------------------------------------------------------------

    private static void hookWifiInfo(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.net.wifi.WifiInfo");

            Method getSSID = cls.getMethod("getSSID");
            module.hook(getSSID).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String ssid = getString(prefs, Constant.XFlag.WIFI_SSID);
                if (ssid.isEmpty()) return chain.proceed();
                // WifiInfo.getSSID() surrounds the name with double-quotes
                return "\"" + ssid + "\"";
            });

            Method getBSSID = cls.getMethod("getBSSID");
            module.hook(getBSSID).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                // BSSID is not exposed in the UI; an empty value means "no override"
                String bssid = getString(prefs, Constant.XFlag.WIFI_BSSID);
                if (bssid.isEmpty()) return chain.proceed();
                return bssid;
            });

            Method getMacAddress = cls.getMethod("getMacAddress");
            module.hook(getMacAddress).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
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

    private static void hookGsmCellLocation(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.telephony.gsm.GsmCellLocation");

            Method getLac = cls.getMethod("getLac");
            module.hook(getLac).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
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
            module.hook(getCid).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
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

    private static void hookWifiScanResults(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.net.wifi.WifiManager");

            Method getScanResults = cls.getMethod("getScanResults");
            module.hook(getScanResults).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                logSpoofed("WifiManager#getScanResults");
                return Collections.emptyList();
            });

            Method startScan = cls.getMethod("startScan");
            module.hook(startScan).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                // Return true to satisfy callers without triggering a real scan.
                return true;
            });

            Log.i(TAG, "hookWifiScanResults installed");
        } catch (Exception e) {
            Log.w(TAG, "hookWifiScanResults failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Cell info hook (TelephonyManager)
    // -----------------------------------------------------------------------

    private static void hookAllCellInfo(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.telephony.TelephonyManager");

            Method getAllCellInfo = cls.getMethod("getAllCellInfo");
            module.hook(getAllCellInfo).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                logSpoofed("TelephonyManager#getAllCellInfo");
                return Collections.emptyList();
            });

            // getCellLocation is present on older APIs but may be absent on API 29+.
            try {
                Method getCellLocation = cls.getMethod("getCellLocation");
                module.hook(getCellLocation).intercept(chain -> {
                    SharedPreferences prefs = getPrefs(module);
                    if (!isEnabled(prefs)) return chain.proceed();
                    String lacStr = getString(prefs, Constant.XFlag.CELL_LAC);
                    String cidStr = getString(prefs, Constant.XFlag.CELL_ID);
                    if (lacStr.isEmpty() && cidStr.isEmpty()) return chain.proceed();
                    try {
                        GsmCellLocation fake = new GsmCellLocation();
                        int lac = lacStr.isEmpty() ? 0 : Integer.parseInt(lacStr);
                        int cid = cidStr.isEmpty() ? 0 : Integer.parseInt(cidStr);
                        fake.setLacAndCid(lac, cid);
                        logSpoofed("TelephonyManager#getCellLocation");
                        return fake;
                    } catch (Exception e) {
                        return chain.proceed();
                    }
                });
            } catch (Exception ignored) {
                // getCellLocation may be absent on some API levels — safe to skip.
            }

            Log.i(TAG, "hookAllCellInfo installed");
        } catch (Exception e) {
            Log.w(TAG, "hookAllCellInfo failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // AMap SDK location hook
    // -----------------------------------------------------------------------

    private static void hookAMapLocation(XposedModule module) {
        try {
            Class<?> cls = Class.forName("com.amap.api.location.AMapLocation");

            Method getLatitude = cls.getMethod("getLatitude");
            module.hook(getLatitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    logSpoofed("AMapLocation#getLatitude");
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method getLongitude = cls.getMethod("getLongitude");
            module.hook(getLongitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LONGITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    logSpoofed("AMapLocation#getLongitude");
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method setLatitude = cls.getMethod("setLatitude", double.class);
            module.hook(setLatitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LATITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return chain.proceed(new Object[]{Double.parseDouble(val)});
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Method setLongitude = cls.getMethod("setLongitude", double.class);
            module.hook(setLongitude).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String val = getString(prefs, Constant.XFlag.LONGITUDE);
                if (val.isEmpty()) return chain.proceed();
                try {
                    return chain.proceed(new Object[]{Double.parseDouble(val)});
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            Log.i(TAG, "hookAMapLocation installed");
        } catch (Exception e) {
            Log.w(TAG, "hookAMapLocation failed: " + e.getMessage());
        }
    }
}
