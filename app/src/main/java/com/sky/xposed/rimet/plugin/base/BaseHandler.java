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

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.sky.xposed.rimet.plugin.interfaces.XConfig;
import com.sky.xposed.rimet.plugin.interfaces.XHandler;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Created by sky on 2018/9/24.
 *
 * Adapted for libxposed API: removed de.robv.android.xposed.XposedHelpers dependencies;
 * field/method access now uses plain Java reflection.
 */
public abstract class BaseHandler implements XHandler {

    private static final String TAG = "BaseHandler";

    private final XPluginManager mPluginManager;
    private final Context mContext;
    private final Handler mHandler;
    private final XConfig mXConfig;
    private final ClassLoader mClassLoader;

    public BaseHandler(XPluginManager pluginManager) {
        mPluginManager = pluginManager;
        mContext = mPluginManager.getContext();
        mHandler = mPluginManager.getHandler();
        mXConfig = mPluginManager.getVersionManager().getSupportConfig();
        mClassLoader = mPluginManager.getClassLoader();
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

    public String getXString(int key) {
        return getXConfig().get(key);
    }

    // -----------------------------------------------------------------------
    // View click helpers
    // -----------------------------------------------------------------------

    public View findViewById(View view, String id) {
        if (view == null) return null;
        return view.findViewById(getViewId(view.getContext(), id));
    }

    public void mainPerformClick(final View viewGroup, final String id) {
        mHandler.post(() -> performClick(viewGroup, id));
    }

    public void mainPerformClick(final View view) {
        mHandler.post(() -> performClick(view));
    }

    public void performClick(View viewGroup, String id) {
        if (viewGroup == null) return;
        Context context = viewGroup.getContext();
        performClick(viewGroup.findViewById(getViewId(context, id)));
    }

    public void performClick(View view) {
        if (view != null && view.isShown()) {
            view.performClick();
        }
    }

    public void postDelayed(Runnable runnable, long delayMillis) {
        mHandler.postDelayed(runnable, delayMillis);
    }

    /**
     * Resolves a resource id by name within the hooked app's package.
     * Replaces com.sky.xposed.common.util.ResourceUtil.getId().
     */
    protected int getViewId(Context context, String idName) {
        return context.getResources().getIdentifier(idName, "id", context.getPackageName());
    }

    // -----------------------------------------------------------------------
    // Reflection helpers — replace XposedHelpers.* calls
    // -----------------------------------------------------------------------

    public Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = findFieldInHierarchy(obj.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            Log.e(TAG, "getObjectField(" + fieldName + ") failed", e);
            return null;
        }
    }

    public int getIntField(Object obj, String fieldName) {
        Object value = getObjectField(obj, fieldName);
        return value instanceof Integer ? (Integer) value : 0;
    }

    /**
     * Calls an instance method by name using reflection, matching by parameter count.
     * Replaces XposedHelpers.callMethod().
     */
    public Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)
                        && method.getParameterCount() == args.length) {
                    try {
                        method.setAccessible(true);
                        return method.invoke(obj, args);
                    } catch (Exception e) {
                        Log.e(TAG, "callMethod(" + methodName + ") failed", e);
                        return null;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        Log.w(TAG, "callMethod: method not found: " + methodName);
        return null;
    }

    /**
     * Calls a static method by name using reflection, matching by parameter count.
     * Replaces XposedHelpers.callStaticMethod().
     */
    public Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)
                    && method.getParameterCount() == args.length) {
                try {
                    method.setAccessible(true);
                    return method.invoke(null, args);
                } catch (Exception e) {
                    Log.e(TAG, "callStaticMethod(" + methodName + ") failed", e);
                    return null;
                }
            }
        }
        Log.w(TAG, "callStaticMethod: method not found: " + methodName);
        return null;
    }

    /**
     * Creates a new instance by trying all constructors with the given argument count.
     * Replaces XposedHelpers.newInstance().
     */
    public Object newInstance(Class<?> clazz, Object... args) {
        for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == args.length) {
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                } catch (Exception ignored) {
                }
            }
        }
        Log.w(TAG, "newInstance: no matching constructor found in " + clazz.getName());
        return null;
    }

    /**
     * Finds a class by name using the hooked app's ClassLoader.
     */
    public Class<?> findClass(String className) {
        try {
            return mClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "findClass: class not found: " + className);
            return null;
        }
    }

    public Class<?> findClass(int classKey) {
        return findClass(getXString(classKey));
    }

    // -----------------------------------------------------------------------
    // parseInt / parseLong helpers (replacing ConversionUtil from xposed-common)
    // -----------------------------------------------------------------------

    protected static int parseInt(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected static long parseLong(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String fieldName)
            throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
