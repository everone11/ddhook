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

package com.sky.xposed.rimet.plugin.base;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.sky.xposed.rimet.plugin.interfaces.XConfig;
import com.sky.xposed.rimet.plugin.interfaces.XConfigManager;
import com.sky.xposed.rimet.plugin.interfaces.XPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Created by sky on 2018/9/24.
 *
 * Adapted for libxposed API: removed de.robv.android.xposed.* dependencies.
 * Hook methods now use XposedInterface.HookBuilder/Chain instead of MethodHook.
 */
public abstract class BasePlugin implements XPlugin {

    private static final String TAG = "BasePlugin";

    private final XPluginManager mPluginManager;
    private final Context mContext;
    private final ClassLoader mClassLoader;
    private final XposedInterface mXposedInterface;
    private final XConfig mXConfig;
    private final XConfigManager mConfigManager;

    public BasePlugin(XPluginManager pluginManager) {
        mPluginManager = pluginManager;
        mContext = pluginManager.getContext();
        mClassLoader = pluginManager.getClassLoader();
        mXposedInterface = pluginManager.getXposedInterface();
        mXConfig = mPluginManager.getVersionManager().getSupportConfig();
        mConfigManager = pluginManager.getConfigManager();
    }

    @Override
    public boolean isHandler() {
        return true;
    }

    @Override
    public void initialization() {
    }

    @Override
    public void release() {
    }

    @Override
    public void openSettings(Activity activity) {
    }

    @Override
    public boolean isEnable(int flag, boolean defValue) {
        return mConfigManager.getBoolean(flag, defValue);
    }

    @Override
    public void setEnable(int flag, boolean enable) {
        mConfigManager.putBoolean(flag, enable);
    }

    public Context getContext() {
        return mContext;
    }

    public XPluginManager getPluginManager() {
        return mPluginManager;
    }

    public XConfig getXConfig() {
        return mXConfig;
    }

    public XConfigManager getConfigManager() {
        return mConfigManager;
    }

    public ClassLoader getClassLoader() {
        return mClassLoader;
    }

    public String getXString(int key) {
        return getXConfig().get(key);
    }

    // -----------------------------------------------------------------------
    // Hook helpers — wraps XposedInterface with a fluent before/after API.
    // -----------------------------------------------------------------------

    /**
     * Find and prepare a method hook by class-key (from M.classz) and method-key (from M.method).
     * Parameter types must be Class<?> instances or fully-qualified class name Strings.
     */
    protected HookHelper findMethod(int classKey, int methodKey, Object... parameterTypes) {
        return findMethod(getXString(classKey), getXString(methodKey), parameterTypes);
    }

    protected HookHelper findMethod(String className, String methodName, Object... parameterTypes) {
        if (className == null || className.isEmpty()) {
            // Config key is not mapped for the current DingTalk version — skip silently.
            return HookHelper.EMPTY;
        }
        try {
            Class<?> clazz = mClassLoader.loadClass(className);
            return findMethod(clazz, methodName, parameterTypes);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Class not found: " + className);
            return HookHelper.EMPTY;
        }
    }

    protected HookHelper findMethod(Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            Class<?>[] params = resolveParamTypes(parameterTypes);
            Method method = clazz.getDeclaredMethod(methodName, params);
            method.setAccessible(true);
            return new HookHelper(method, mXposedInterface);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "Method not found: " + clazz.getName() + "#" + methodName);
            return HookHelper.EMPTY;
        } catch (Exception e) {
            Log.e(TAG, "findMethod error: " + methodName, e);
            return HookHelper.EMPTY;
        }
    }

    /**
     * Resolve Object[] varargs (may contain Class<?> or String) to Class<?>[].
     */
    private Class<?>[] resolveParamTypes(Object[] parameterTypes) throws ClassNotFoundException {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] params = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object pt = parameterTypes[i];
            if (pt instanceof Class) {
                params[i] = (Class<?>) pt;
            } else if (pt instanceof String) {
                params[i] = mClassLoader.loadClass((String) pt);
            } else {
                throw new IllegalArgumentException("Invalid param type at index " + i + ": " + pt);
            }
        }
        return params;
    }

    // -----------------------------------------------------------------------
    // HookHelper — fluent wrapper around XposedInterface.HookBuilder
    // -----------------------------------------------------------------------

    /**
     * A helper that wraps a Method/Constructor and an XposedInterface,
     * providing before()/after() hook registration analogous to the
     * legacy xposed-javax MethodHook API.
     */
    public static class HookHelper {

        /** Returned when the target method/class could not be found. All operations are no-ops. */
        static final HookHelper EMPTY = new HookHelper(null, null);

        private final java.lang.reflect.Executable mExecutable;
        private final XposedInterface mXposedInterface;

        HookHelper(java.lang.reflect.Executable executable, XposedInterface xposedInterface) {
            mExecutable = executable;
            mXposedInterface = xposedInterface;
        }

        /**
         * Registers a "before" hook.
         * <p>The provided {@link XposedInterface.Hooker} receives a {@link XposedInterface.Chain}.
         * To skip the original method, do NOT call {@code chain.proceed()} and return
         * the desired result (e.g. {@code return null} for void methods).
         * To run the original after your code, call {@code return chain.proceed();}.</p>
         */
        public XposedInterface.HookHandle before(XposedInterface.Hooker hooker) {
            if (mExecutable == null || mXposedInterface == null) return null;
            return mXposedInterface.hook(mExecutable).intercept(hooker);
        }

        /**
         * Registers an "after" hook.
         * <p>The original method is called first; then the provided {@link AfterHooker} is invoked
         * with the same {@link XposedInterface.Chain} (result available via {@code chain} context).</p>
         */
        public XposedInterface.HookHandle after(AfterHooker hooker) {
            if (mExecutable == null || mXposedInterface == null) return null;
            return mXposedInterface.hook(mExecutable).intercept(chain -> {
                Object result = chain.proceed();
                hooker.after(chain);
                return result;
            });
        }

        /** Functional interface for after-hooks, analogous to the old XC_MethodHook.afterHookedMethod. */
        @FunctionalInterface
        public interface AfterHooker {
            void after(XposedInterface.Chain chain) throws Throwable;
        }
    }
}
