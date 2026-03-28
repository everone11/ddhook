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

package com.sky.xposed.rimet.plugin;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.sky.xposed.rimet.BuildConfig;
import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.ConfigManager;
import com.sky.xposed.rimet.plugin.dingding.DingDingHandler;
import com.sky.xposed.rimet.plugin.dingding.DingDingPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XConfigManager;
import com.sky.xposed.rimet.plugin.interfaces.XPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;
import com.sky.xposed.rimet.plugin.interfaces.XVersionManager;
import com.sky.xposed.rimet.plugin.main.SettingsPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Created by sky on 2018/9/24.
 *
 * Adapted for libxposed API:
 *  - XC_LoadPackage.LoadPackageParam replaced with ClassLoader + XposedInterface.
 *  - Removed Picasso, Alog, ToastUtil (xposed-common) dependencies.
 */
public class PluginManager implements XPluginManager {

    private static final String TAG = "PluginManager";

    private static XPluginManager sXPluginManager;

    private final Context mContext;
    private final Handler mHandler;
    private final ClassLoader mClassLoader;
    private final XposedInterface mXposedInterface;
    private final XVersionManager mVersionManager;
    private XConfigManager mConfigManager;
    private final Map<Class, Object> mObjectMap = new HashMap<>();

    private final SparseArray<XPlugin> mXPlugins = new SparseArray<>();

    private PluginManager(Build build) {
        mContext = build.mContext;
        mClassLoader = build.mClassLoader;
        mXposedInterface = build.mXposedInterface;
        mVersionManager = build.mXVersionManager;

        mHandler = new PluginHandler();

        sXPluginManager = this;
    }

    public static XPluginManager getInstance() {
        return sXPluginManager;
    }

    /**
     * 开始处理需要Hook的插件
     */
    @Override
    public void handleLoadPackage() {

        if (mXPlugins.size() != 0 || !getVersionManager().isSupportVersion()) {
            Log.d(TAG, "暂时不需要处理加载的包!");
            return;
        }

        loadPlugin();

        for (int i = 0; i < mXPlugins.size(); i++) {
            handleLoadPackage(mXPlugins.get(mXPlugins.keyAt(i)));
        }
    }

    @Override
    public void release() {
        mXPlugins.clear();
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public Handler getHandler() {
        return mHandler;
    }

    @Override
    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    @Override
    public XposedInterface getXposedInterface() {
        return mXposedInterface;
    }

    @Override
    public XVersionManager getVersionManager() {
        return mVersionManager;
    }

    @Override
    public XConfigManager getConfigManager() {
        if (mConfigManager == null) {
            // Read settings from the module's own SharedPreferences (written by the UI Activity).
            // We use createPackageContext so the hook running in DingTalk's process can access
            // the module's data directory — LSPosed adjusts SELinux labels to allow this.
            Context configContext = getModuleContext();
            mConfigManager = new ConfigManager
                    .Build(this)
                    .setContext(configContext)
                    .setConfigName(Constant.Name.RIMET)
                    .build();
        }
        return mConfigManager;
    }

    /**
     * Returns a Context pointing at the module's own package data directory so that
     * SharedPreferences written by the UI Activity can be read here in the hooked process.
     * Falls back to the hooked-app context on failure.
     */
    private Context getModuleContext() {
        try {
            return mContext.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Module package context not found, falling back to hooked context", e);
            return mContext;
        }
    }

    /**
     * 获取所有插件
     */
    @Override
    public List<XPlugin> getXPlugins(int flag) {

        List<XPlugin> xPlugins = new ArrayList<>();

        for (int i = 0; i < mXPlugins.size(); i++) {
            XPlugin xPlugin = mXPlugins.get(mXPlugins.keyAt(i));
            if ((xPlugin.getInfo().getId() & flag) > 0) {
                xPlugins.add(xPlugin);
            }
        }
        return xPlugins;
    }

    @Override
    public XPlugin getXPlugin(XPlugin.Info info) {
        return getXPluginById(info.getId());
    }

    @Override
    public XPlugin getXPluginById(int id) {
        return mXPlugins.get(id);
    }

    @Override
    public <T> T getObject(Class<T> tClass) {

        if (mObjectMap.containsKey(tClass)) {
            return (T) mObjectMap.get(tClass);
        }

        try {
            T result = tClass.getConstructor(XPluginManager.class).newInstance(this);
            mObjectMap.put(tClass, result);
            return result;
        } catch (Throwable tr) {
            Log.e(TAG, "getObject: instantiation failed", tr);
        }
        return null;
    }

    /**
     * 开始处理需要Hook的插件
     */
    private void handleLoadPackage(XPlugin xPlugin) {

        if (!xPlugin.isHandler()) return;

        try {
            xPlugin.initialization();
            xPlugin.onHandleLoadPackage();
        } catch (Throwable tr) {
            Log.e(TAG, "handleLoadPackage异常", tr);
        }
    }

    /**
     * 加载需要处理的插件
     */
    private void loadPlugin() {

        addPlugin(new SettingsPlugin(this));
        addPlugin(new DingDingPlugin
                .Build(this)
                .setHandler(new DingDingHandler(this))
                .build());
    }

    private void addPlugin(XPlugin xPlugin) {
        mXPlugins.append(xPlugin.getInfo().getId(), xPlugin);
    }

    private static final class PluginHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }

    public static class Build {

        private Context mContext;
        private ClassLoader mClassLoader;
        private XposedInterface mXposedInterface;
        private XVersionManager mXVersionManager;

        public Build(Context context, XposedInterface xposedInterface) {
            mContext = context;
            mXposedInterface = xposedInterface;
        }

        public Build setClassLoader(ClassLoader classLoader) {
            mClassLoader = classLoader;
            return this;
        }

        public Build setVersionManager(XVersionManager xVersionManager) {
            mXVersionManager = xVersionManager;
            return this;
        }

        public XPluginManager build() {
            return new PluginManager(this);
        }
    }
}
