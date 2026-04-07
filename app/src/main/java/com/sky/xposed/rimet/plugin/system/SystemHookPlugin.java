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
import android.net.wifi.SupplicantState;
import android.util.Log;

import com.sky.xposed.rimet.Constant;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

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
    // Access is synchronized to ensure thread-safe check-then-act.
    // -----------------------------------------------------------------------
    private static SharedPreferences sPrefsCache = null;
    private static long sPrefsLastRefreshMs = 0L;
    private static final long PREFS_CACHE_TTL_MS = 2_000L;

    // Rate-limited spoof-log using AtomicLong for lock-free CAS update.
    private static final AtomicLong sLastSpoofLogMs = new AtomicLong(0L);
    private static final long LOG_INTERVAL_MS = 5_000L;

    private SystemHookPlugin() {
    }

    /**
     * Register all system-level hooks using the provided {@link XposedModule}.
     * Safe to call from {@code onPackageReady} before any app Context is available.
     */
    public static void setup(XposedModule module) {
        hookLocation(module);
        hookLocationManager(module);
        hookWifiInfo(module);
        hookWifiScanResults(module);
        hookGsmCellLocation(module);
        hookAllCellInfo(module);
        module.log(Log.INFO, TAG, "System hooks installed");
    }

    // -----------------------------------------------------------------------
    // SharedPreferences helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the module's SharedPreferences, refreshed via LibXposed IPC at most
     * once per {@link #PREFS_CACHE_TTL_MS} to avoid per-call IPC overhead.
     * Synchronized to guard the check-then-act against concurrent refreshes.
     */
    static synchronized SharedPreferences getPrefs(XposedModule module) {
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

    /** Rate-limited info log using CAS to prevent duplicate entries within the window. */
    static void logSpoofed(XposedModule module, String field) {
        long now = System.currentTimeMillis();
        long last = sLastSpoofLogMs.get();
        if (now - last > LOG_INTERVAL_MS && sLastSpoofLogMs.compareAndSet(last, now)) {
            module.log(Log.INFO, TAG, "Spoofing " + field);
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
                    logSpoofed(module, "android.location.Location#getLatitude");
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
                    logSpoofed(module, "android.location.Location#getLongitude");
                    return Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    return chain.proceed();
                }
            });

            module.log(Log.INFO, TAG, "hookLocation installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookLocation failed", e);
        }
    }

    // -----------------------------------------------------------------------
    // LocationManager hooks
    // -----------------------------------------------------------------------

    private static void hookLocationManager(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.location.LocationManager");

            // isProviderEnabled(String) — return true for GPS/network when spoofing is active
            // so that AMap SDK and the system start location updates.
            Method isProviderEnabled = cls.getMethod("isProviderEnabled", String.class);
            module.hook(isProviderEnabled).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                // Only activate if the user has set a latitude to spoof.
                String lat = getString(prefs, Constant.XFlag.LATITUDE);
                if (lat.isEmpty()) return chain.proceed();
                String provider = (String) chain.getArg(0);
                if ("gps".equals(provider) || "network".equals(provider)) {
                    logSpoofed(module, "LocationManager#isProviderEnabled");
                    return true;
                }
                return chain.proceed();
            });

            module.log(Log.INFO, TAG, "hookLocationManager installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookLocationManager failed", e);
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

            // getNetworkId() — return a valid (non-negative) ID so apps don't treat WiFi
            // as disconnected.  Apps typically check getNetworkId() != -1 before reading SSID.
            Method getNetworkId = cls.getMethod("getNetworkId");
            module.hook(getNetworkId).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String ssid = getString(prefs, Constant.XFlag.WIFI_SSID);
                if (ssid.isEmpty()) return chain.proceed();
                return 1;
            });

            // getSupplicantState() — return COMPLETED so apps see a fully-associated WiFi.
            Method getSupplicantState = cls.getMethod("getSupplicantState");
            module.hook(getSupplicantState).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String ssid = getString(prefs, Constant.XFlag.WIFI_SSID);
                if (ssid.isEmpty()) return chain.proceed();
                return SupplicantState.COMPLETED;
            });

            module.log(Log.INFO, TAG, "hookWifiInfo installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookWifiInfo failed", e);
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

            module.log(Log.INFO, TAG, "hookGsmCellLocation installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookGsmCellLocation failed", e);
        }
    }

    private static void hookWifiScanResults(XposedModule module) {
        try {
            Class<?> cls = Class.forName("android.net.wifi.WifiManager");

            // isWifiEnabled() — return true so apps don't bail out before reading connection info.
            Method isWifiEnabled = cls.getMethod("isWifiEnabled");
            module.hook(isWifiEnabled).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                String ssid = getString(prefs, Constant.XFlag.WIFI_SSID);
                if (ssid.isEmpty()) return chain.proceed();
                return true;
            });

            Method getScanResults = cls.getMethod("getScanResults");
            module.hook(getScanResults).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                logSpoofed(module, "WifiManager#getScanResults");
                return Collections.emptyList();
            });

            Method startScan = cls.getMethod("startScan");
            module.hook(startScan).intercept(chain -> {
                SharedPreferences prefs = getPrefs(module);
                if (!isEnabled(prefs)) return chain.proceed();
                // Return true to satisfy callers without triggering a real scan.
                return true;
            });

            module.log(Log.INFO, TAG, "hookWifiScanResults installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookWifiScanResults failed", e);
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
                logSpoofed(module, "TelephonyManager#getAllCellInfo");
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
                        // Construct a GsmCellLocation via reflection to avoid direct use of
                        // the deprecated android.telephony.gsm.GsmCellLocation type.
                        Class<?> gsmCls = Class.forName("android.telephony.gsm.GsmCellLocation");
                        Object fake = gsmCls.getDeclaredConstructor().newInstance();
                        int lac = lacStr.isEmpty() ? 0 : Integer.parseInt(lacStr);
                        int cid = cidStr.isEmpty() ? 0 : Integer.parseInt(cidStr);
                        gsmCls.getMethod("setLacAndCid", int.class, int.class)
                                .invoke(fake, lac, cid);
                        logSpoofed(module, "TelephonyManager#getCellLocation");
                        return fake;
                    } catch (Exception e) {
                        return chain.proceed();
                    }
                });
            } catch (Exception ignored) {
                // getCellLocation may be absent on some API levels — safe to skip.
            }

            // getNetworkOperator() / getSimOperator() — return "{mcc}{mnc}" (e.g. "46000")
            // so that apps that validate the carrier identity before trusting cell data see
            // a consistent spoofed network.  Only active when both MCC and MNC are configured.
            try {
                Method getNetworkOperator = cls.getMethod("getNetworkOperator");
                module.hook(getNetworkOperator).intercept(chain -> {
                    SharedPreferences prefs = getPrefs(module);
                    if (!isEnabled(prefs)) return chain.proceed();
                    String mcc = getString(prefs, Constant.XFlag.CELL_MCC);
                    String mnc = getString(prefs, Constant.XFlag.CELL_MNC);
                    if (mcc.isEmpty() || mnc.isEmpty()) return chain.proceed();
                    logSpoofed(module, "TelephonyManager#getNetworkOperator");
                    return mcc + mnc;
                });
            } catch (Exception ignored) {
                // Absent on some devices — safe to skip.
            }

            try {
                Method getSimOperator = cls.getMethod("getSimOperator");
                module.hook(getSimOperator).intercept(chain -> {
                    SharedPreferences prefs = getPrefs(module);
                    if (!isEnabled(prefs)) return chain.proceed();
                    String mcc = getString(prefs, Constant.XFlag.CELL_MCC);
                    String mnc = getString(prefs, Constant.XFlag.CELL_MNC);
                    if (mcc.isEmpty() || mnc.isEmpty()) return chain.proceed();
                    logSpoofed(module, "TelephonyManager#getSimOperator");
                    return mcc + mnc;
                });
            } catch (Exception ignored) {
                // Absent on some devices — safe to skip.
            }

            module.log(Log.INFO, TAG, "hookAllCellInfo installed");
        } catch (Throwable e) {
            module.log(Log.WARN, TAG, "hookAllCellInfo failed", e);
        }
    }

}
