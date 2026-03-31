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

package com.sky.xposed.rimet;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.data.VersionManager;
import com.sky.xposed.rimet.plugin.PluginManager;
import com.sky.xposed.rimet.plugin.system.AlipayMapHookPlugin;
import com.sky.xposed.rimet.plugin.system.DingTalkDeepHookPlugin;
import com.sky.xposed.rimet.plugin.system.SystemHookPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XConfig;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;
import com.sky.xposed.rimet.plugin.interfaces.XVersionManager;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Xposed module entry point for ddhook (DingTalk hook).
 *
 * <p>Migrated from {@code mikecraig6810/xposed-rimet} — original entry used the legacy
 * {@code IXposedHookLoadPackage} interface. This version extends {@link XposedModule}
 * from {@code io.github.libxposed:api:101.0.0} and implements the modern lifecycle.</p>
 *
 * <p>Key differences from the legacy API:</p>
 * <ul>
 *   <li>{@code handleLoadPackage(LoadPackageParam)} → {@link #onPackageReady(PackageReadyParam)}</li>
 *   <li>No {@code assets/xposed_init}; module is declared via {@code AndroidManifest.xml} meta-data.</li>
 *   <li>Hooking uses {@link XposedInterface#hook(java.lang.reflect.Executable)} builder pattern
 *       instead of {@code XposedHelpers.findAndHookMethod}.</li>
 * </ul>
 */
public class Main extends XposedModule {

    private static final String TAG = "ddhook";

    public Main() {
        super();
    }

    /**
     * Called when our module itself is loaded (process-level init).
     */
    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "ddhook module loaded in process: " + param.getProcessName());
    }

    /**
     * Called when a target package's classloader is ready (analogous to handleLoadPackage).
     * System-level hooks (Location/WiFi/Cell) are applied for every in-scope package.
     * DingTalk-specific hooks are applied only when the DingTalk package is ready and a
     * matching version config is available.
     */
    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam lpParam) {

        // Apply system-level hooks (Location/WiFi/Cell) for every in-scope package and process
        SystemHookPlugin.setup(this);

        // All remaining hooks are DingTalk-specific.
        if (!Constant.Rimet.PACKAGE_NAME.equals(lpParam.getPackageName())) return;

        ClassLoader classLoader = lpParam.getClassLoader();

        // Defer DingTalk internal class hooks until Application.onCreate() fires, which is
        // after MultiDex.install() completes and all secondary dex classes are resolvable.
        try {
            Class<?> appCls = Class.forName("android.app.Application");
            Method appOnCreate = appCls.getDeclaredMethod("onCreate");
            hook(appOnCreate).intercept(chain -> {
                try {
                    DingTalkDeepHookPlugin.setup(Main.this, classLoader);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "DingTalkDeepHookPlugin.setup failed", t);
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Application.onCreate hooked for DingTalk deep hooks");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook Application.onCreate for deep hooks", t);
        }

        // Apply Alipay map model hooks (LatLonPoint, IndoorLocation, LBSWifiItemInfo,
        // SearchPoiRequest) — these Alipay SDK classes are bundled inside DingTalk.
        AlipayMapHookPlugin.setup(this, classLoader);

        // DDApplication.onCreate hook is only meaningful in the main (first) process.
        if (!lpParam.isFirstPackage()) return;

        log(Log.INFO, TAG, "DingTalk package ready — setting up hooks");

        // Obtain a Context from the system to check DingTalk's installed version.
        Context systemContext = getSystemContext();
        if (systemContext == null) {
            log(Log.ERROR, TAG, "Could not obtain system context — aborting DingTalk hook setup");
            return;
        }

        XVersionManager versionManager = new VersionManager.Build(systemContext).build();

        XConfig config = versionManager.getSupportConfig();

        if (config == null) {
            log(Log.WARN, TAG, "Unsupported DingTalk version: " + versionManager.getVersionName()
                    + " (supported: " + versionManager.getSupportVersion()
                    + ") — skipping DingTalk-specific hooks");
            return;
        }

        // The DDApplication class is version-specific; look it up from the version config.
        String ddApplicationClassName = config.get(M.classz.class_dingtalkbase_multidexsupport_DDApplication);
        String launcherApplicationClassName = config.get(M.classz.class_rimet_LauncherApplication);

        try {
            Class<?> ddAppClass = classLoader.loadClass(ddApplicationClassName);
            Method onCreateMethod = ddAppClass.getDeclaredMethod("onCreate");

            hook(onCreateMethod).intercept(chain -> {

                Application application = (Application) chain.getThisObject();

                // Only initialise once for the LauncherApplication (primary app instance)
                if (!launcherApplicationClassName.equals(application.getClass().getName())) {
                    return chain.proceed();
                }

                Context context = application.getApplicationContext();

                XPluginManager pluginManager = new PluginManager
                        .Build(context, this)
                        .setClassLoader(classLoader)
                        .setVersionManager(versionManager)
                        .build();

                // Run original Application.onCreate first, then start hooks
                Object result = chain.proceed();
                pluginManager.handleLoadPackage();
                return result;
            });

            log(Log.INFO, TAG, "DDApplication.onCreate hooked successfully");

        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "DDApplication class not found: " + ddApplicationClassName, e);
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "DDApplication.onCreate method not found", e);
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "Unexpected error during hook setup", e);
        }
    }

    // -----------------------------------------------------------------------
    // Helper: obtain system Context via reflection (replaces direct ActivityThread usage).
    // -----------------------------------------------------------------------

    private static Context getSystemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentThread = activityThread.getMethod("currentActivityThread");
            Object thread = currentThread.invoke(null);
            Method getSystemContext = activityThread.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(thread);
        } catch (Exception e) {
            Log.e(TAG, "getSystemContext failed", e);
            return null;
        }
    }
}
