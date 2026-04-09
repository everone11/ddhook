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

package com.sky.xposed.rimet.data.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a saved virtual-location preset (address category).
 * Holds all location-spoof parameters so they can be named, stored,
 * and quickly switched between from the settings dialog.
 */
public class LocationPreset {

    public String name     = "";
    public String latitude = "";
    public String longitude = "";
    public String offset   = "";
    public String wifiSsid  = "";
    public String wifiBssid = "";
    public String wifiMac   = "";
    public String cellId  = "";
    public String cellLac = "";
    public String cellMcc = "";
    public String cellMnc = "";

    public LocationPreset() {}

    /** Serialize to a JSONObject for SharedPreferences storage. */
    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("name",      name);
        obj.put("latitude",  latitude);
        obj.put("longitude", longitude);
        obj.put("offset",    offset);
        obj.put("wifiSsid",  wifiSsid);
        obj.put("wifiBssid", wifiBssid);
        obj.put("wifiMac",   wifiMac);
        obj.put("cellId",    cellId);
        obj.put("cellLac",   cellLac);
        obj.put("cellMcc",   cellMcc);
        obj.put("cellMnc",   cellMnc);
        return obj;
    }

    /** Deserialize from a JSONObject. Missing fields default to empty string. */
    public static LocationPreset fromJson(JSONObject obj) {
        LocationPreset p = new LocationPreset();
        p.name      = obj.optString("name",      "");
        p.latitude  = obj.optString("latitude",  "");
        p.longitude = obj.optString("longitude", "");
        p.offset    = obj.optString("offset",    "");
        p.wifiSsid  = obj.optString("wifiSsid",  "");
        p.wifiBssid = obj.optString("wifiBssid", "");
        p.wifiMac   = obj.optString("wifiMac",   "");
        p.cellId    = obj.optString("cellId",    "");
        p.cellLac   = obj.optString("cellLac",   "");
        p.cellMcc   = obj.optString("cellMcc",   "");
        p.cellMnc   = obj.optString("cellMnc",   "");
        return p;
    }
}
