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
import android.net.wifi.SupplicantState;
import android.util.Log;

import com.sky.xposed.rimet.Constant;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModule;

/**
 * System-level Hook plugin that spoofs Location, WiFi and Cell information
 * for any in-scope application (DingTalk, Alipay, etc.).
 *
 * <p>Hooks Android framework APIs directly so the spoofing is version-agnostic
 * and works across any app without needing app-specific class-name mapping.</p>
 *
 * <p>Settings are read from two sources (in priority order):</p>
 * <ol>
 *   <li>The hooked app's own local SharedPreferences (written by the in-app
 *       settings dialog, zero IPC overhead).  Used when {@link #sAppContext} is
 *       set and the file already contains an {@code ENABLE_LOCATION} key.</li>
 *   <li>The module's SharedPreferences via {@link XposedModule#getRemotePreferences}
 *       (IPC-backed, written by the standalone {@code MainActivity}).  A 2-second
 *       TTL cache minimises IPC overhead on every hooked call.</li>
 * </ol>
 */
public class SystemHookPlugin {

    private static final String TAG = "SystemHookPlugin";

    /**
     * DingTalk's Application context, set from {@code Main.java} once
     * {@code Application.onCreate()} has finished.  Volatile so that writes
     * from the main thread are immediately visible in hook threads.
     */
    public static volatile Context sAppContext = null;

    // -----------------------------------------------------------------------
    // Remote-preference cache — refreshed at most once every PREFS_CACHE_TTL_MS.
    // Access is synchronized to guard the check-then-act sequence.
    // -----------------------------------------------------------------------
    private static SharedPreferences sPrefsCache = null;
    private static long sPrefsLastRefreshMs = 0L;
    private static final long PREFS_CACHE_TTL_MS = 2_000L;

    // Rate-limited spoof-log using AtomicLong for lock-free CAS update.
    private static final AtomicLong sLastSpoofLogMs = new AtomicLong(0L);
    private static final long LOG_INTERVAL_MS = 5_000L;

    // -----------------------------------------------------------------------
    // Random GPS offset cache — keeps lat and lon offsets consistent within
    // the same base-coordinate + offset-distance combination.
    // -----------------------------------------------------------------------

    /** Approximate metres per degree of latitude (constant at all latitudes). */
    private static final double METERS_PER_DEGREE_LAT = 111_111.0;

    /** Minimum cos(lat) value; guards against division by zero near the poles. */
    private static final double MIN_COS_LAT_THRESHOLD = 1e-10;

    /** Stores [baseLat, baseLon, offsetMeters, effectiveLat, effectiveLon]. */
    private static double[] sGpsOffsetCache = null;

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
     * Returns the SharedPreferences to use for location-spoof decisions.
     *
     * <p>Priority:
     * <ol>
     *   <li>The hooked app's own local SharedPreferences (populated by the
     *       in-app location dialog, zero IPC, same process) — used when
     *       {@link #sAppContext} is set <em>and</em> the file already has an
     *       {@code ENABLE_LOCATION} entry (i.e. the user has saved settings
     *       via the in-DingTalk dialog at least once).</li>
     *   <li>Remote preferences from the module process (populated by the
     *       standalone {@code MainActivity}), refreshed at most every
     *       {@link #PREFS_CACHE_TTL_MS} ms.</li>
     * </ol>
     */
    static synchronized SharedPreferences getPrefs(XposedModule module) {
        // --- Primary: hooked-app local prefs (no IPC needed) ---
        Context ctx = sAppContext;
        if (ctx != null) {
            SharedPreferences local = ctx.getSharedPreferences(
                    Constant.Name.RIMET, Context.MODE_PRIVATE);
            if (local.contains(Integer.toString(Constant.XFlag.ENABLE_LOCATION))) {
                return local;
            }
        }
        // --- Secondary: module-process prefs via XposedProvider (IPC) ---
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

    /**
     * Returns a two-element array {@code [effectiveLat, effectiveLon]} with the random offset
     * applied.  The offset pair is cached and only regenerated when the base coordinates or
     * offset distance change, ensuring latitude and longitude share a consistent random delta.
     *
     * <p>Offset algorithm: picks a random direction (uniform over [0, 2π)) and a random radius
     * (uniform over a disc of {@code offsetMeters}) then converts to degree increments.</p>
     *
     * @param baseLat     the configured (unperturbed) latitude in degrees.
     * @param baseLon     the configured (unperturbed) longitude in degrees.
     * @param offsetMeters maximum random offset radius in metres; 0 means no offset.
     * @return {@code double[]{effectiveLat, effectiveLon}}
     */
    static synchronized double[] getEffectiveCoords(double baseLat, double baseLon, double offsetMeters) {
        double[] cache = sGpsOffsetCache;
        if (cache != null
                && Double.compare(cache[0], baseLat) == 0
                && Double.compare(cache[1], baseLon) == 0
                && Double.compare(cache[2], offsetMeters) == 0) {
            return new double[]{cache[3], cache[4]};
        }
        double deltaLat = 0.0;
        double deltaLon = 0.0;
        if (offsetMeters > 0) {
            double theta = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * offsetMeters;
            deltaLat = r * Math.cos(theta) / METERS_PER_DEGREE_LAT;
            double cosLat = Math.cos(Math.toRadians(baseLat));
            if (cosLat > MIN_COS_LAT_THRESHOLD) {
                deltaLon = r * Math.sin(theta) / (METERS_PER_DEGREE_LAT * cosLat);
            }
        }
        sGpsOffsetCache = new double[]{baseLat, baseLon, offsetMeters,
                baseLat + deltaLat, baseLon + deltaLon};
        return new double[]{baseLat + deltaLat, baseLon + deltaLon};
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
                    double baseLat = Double.parseDouble(val);
                    String lonVal = getString(prefs, Constant.XFlag.LONGITUDE);
                    String offVal = getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    logSpoofed(module, "android.location.Location#getLatitude");
                    if (lonVal.isEmpty() || offVal.isEmpty()) return baseLat;
                    double offsetMeters = Double.parseDouble(offVal);
                    return getEffectiveCoords(baseLat, Double.parseDouble(lonVal), offsetMeters)[0];
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
                    double baseLon = Double.parseDouble(val);
                    String latVal = getString(prefs, Constant.XFlag.LATITUDE);
                    String offVal = getString(prefs, Constant.XFlag.LOCATION_OFFSET);
                    logSpoofed(module, "android.location.Location#getLongitude");
                    if (latVal.isEmpty() || offVal.isEmpty()) return baseLon;
                    double offsetMeters = Double.parseDouble(offVal);
                    return getEffectiveCoords(Double.parseDouble(latVal), baseLon, offsetMeters)[1];
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
                // An empty BSSID value means "no override".
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
