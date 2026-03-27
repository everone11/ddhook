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

import java.util.List;

/**
 * Created by sky on 2019/3/12.
 */
public interface XVersionManager {

    /**
     * 获取当前版本名
     */
    String getVersionName();

    /**
     * 获取当前版本号
     */
    int getVersionCode();

    /**
     * 判断Hook是否支持当前版本
     */
    boolean isSupportVersion();

    /**
     * 获取支持版本的配置信息,如果没有适配到返回Null
     */
    XConfig getSupportConfig();

    /**
     * 获取插件支持的版本
     */
    List<String> getSupportVersion();
}
