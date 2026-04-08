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

package com.sky.xposed.rimet.plugin.interfaces;

import android.content.Context;
import android.os.Handler;

import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Created by sky on 2019/3/11.
 *
 * Adapted for libxposed API: XC_LoadPackage.LoadPackageParam replaced with
 * ClassLoader + XposedInterface.
 */
public interface XPluginManager {

    /**
     * 返回当前Hook应用的Context
     */
    Context getContext();

    /**
     * 返回程序创建的Handler
     */
    Handler getHandler();

    /**
     * 获取被Hook包的ClassLoader
     */
    ClassLoader getClassLoader();

    /**
     * 获取libxposed接口实例，用于注册Hook
     */
    XposedInterface getXposedInterface();

    /**
     * 获取版本管理对象
     */
    XVersionManager getVersionManager();

    /**
     * 获取配置管理对象
     */
    XConfigManager getConfigManager();

    /**
     * 获取相应flag的插件
     */
    List<XPlugin> getXPlugins(int flag);

    /**
     * 获取插件信息获取指定插件
     */
    XPlugin getXPlugin(XPlugin.Info info);

    /**
     * 获取相应id获取相应插件
     */
    XPlugin getXPluginById(int id);

    /**
     * 开始处理加载的包
     */
    void handleLoadPackage();

    /**
     * 释放
     */
    void release();
}
