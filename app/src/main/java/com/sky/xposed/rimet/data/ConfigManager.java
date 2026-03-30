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

package com.sky.xposed.rimet.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.sky.xposed.rimet.plugin.interfaces.XConfigManager;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created by sky on 2018/12/24.
 */
public class ConfigManager implements XConfigManager {

    private Context mContext;
    private SimplePreferences mSimplePreferences;
    private Map<String, XConfigManager> mManagerMap = new HashMap<>();

    private ConfigManager(Build build) {
        mContext = build.mContext != null ? build.mContext : build.mXPluginManager.getContext();
        if (build.mSharedPreferences != null) {
            mSimplePreferences = new SimplePreferences(build.mSharedPreferences);
        } else if (build.mName != null) {
            mSimplePreferences = new SimplePreferences(mContext, build.mName);
        } else {
            throw new IllegalArgumentException(
                    "ConfigManager.Build requires either setSharedPreferences or setConfigName");
        }
    }

    @Override
    public String getString(int flag, String defValue) {
        return mSimplePreferences.getString(flag, defValue);
    }

    @Override
    public boolean getBoolean(int flag, boolean defValue) {
        return mSimplePreferences.getBoolean(flag, defValue);
    }

    @Override
    public int getInt(int flag, int defValue) {
        return mSimplePreferences.getInt(flag, defValue);
    }

    @Override
    public Set<String> getStringSet(int flag, Set<String> defValue) {
        return mSimplePreferences.getStringSet(flag, defValue);
    }

    @Override
    public void putString(int flag, String value) {
        mSimplePreferences.putString(flag, value);
    }

    @Override
    public void putBoolean(int flag, boolean value) {
        mSimplePreferences.putBoolean(flag, value);
    }

    @Override
    public void putInt(int flag, int value) {
        mSimplePreferences.putInt(flag, value);
    }

    @Override
    public void putStringSet(int flag, Set<String> value) {
        mSimplePreferences.putStringSet(flag, value);
    }

    @Override
    public XConfigManager getConfigManager(String name) {

        if (mManagerMap.containsKey(name)) {
            return mManagerMap.get(name);
        }

        SimplePreferences preferences = new SimplePreferences(mContext, name);
        mManagerMap.put(name, preferences);

        return preferences;
    }

    @Override
    public void release() {
        mManagerMap.clear();
    }

    /**
     * A simple SharedPreferences-backed config manager.
     */
    private final class SimplePreferences implements XConfigManager {

        private SharedPreferences mSharedPreferences;

        /** Construct from a pre-existing SharedPreferences (e.g. from XposedService). */
        public SimplePreferences(SharedPreferences prefs) {
            mSharedPreferences = prefs;
        }

        public SimplePreferences(Context context, String name) {
            mSharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        }

        @Override
        public String getString(int flag, String defValue) {
            return mSharedPreferences.getString(Integer.toString(flag), defValue);
        }

        @Override
        public boolean getBoolean(int flag, boolean defValue) {
            return mSharedPreferences.getBoolean(Integer.toString(flag), defValue);
        }

        @Override
        public int getInt(int flag, int defValue) {
            return mSharedPreferences.getInt(Integer.toString(flag), defValue);
        }

        @Override
        public Set<String> getStringSet(int flag, Set<String> defValue) {
            return mSharedPreferences.getStringSet(Integer.toString(flag), defValue);
        }

        @Override
        public void putString(int flag, String value) {
            mSharedPreferences.edit().putString(Integer.toString(flag), value).apply();
        }

        @Override
        public void putBoolean(int flag, boolean value) {
            mSharedPreferences.edit().putBoolean(Integer.toString(flag), value).apply();
        }

        @Override
        public void putInt(int flag, int value) {
            mSharedPreferences.edit().putInt(Integer.toString(flag), value).apply();
        }

        @Override
        public void putStringSet(int flag, Set<String> value) {
            mSharedPreferences.edit().putStringSet(Integer.toString(flag), value).apply();
        }

        @Override
        public XConfigManager getConfigManager(String name) {
            throw new IllegalArgumentException("不支持当前操作");
        }

        @Override
        public void release() {
            throw new IllegalArgumentException("不支持当前操作");
        }
    }

    public static class Build {

        private XPluginManager mXPluginManager;
        private Context mContext;
        private String mName;
        private SharedPreferences mSharedPreferences;

        public Build(XPluginManager xPluginManager) {
            mXPluginManager = xPluginManager;
        }

        public Build setContext(Context context) {
            mContext = context;
            return this;
        }

        public Build setConfigName(String name) {
            mName = name;
            return this;
        }

        /**
         * Provide a SharedPreferences instance directly.
         * When set, it takes priority over setContext()+setConfigName() for the primary config.
         */
        public Build setSharedPreferences(SharedPreferences prefs) {
            mSharedPreferences = prefs;
            return this;
        }

        public XConfigManager build() {
            return new ConfigManager(this);
        }
    }
}
