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

package com.sky.xposed.rimet.plugin.dingding;

import android.app.Activity;
import android.content.Context;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.data.model.PluginInfo;
import com.sky.xposed.rimet.plugin.base.BasePlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPlugin;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;

import java.util.List;

/**
 * Created by sky on 2019/3/14.
 *
 * Adapted for libxposed API:
 *  - findMethod().before/after now use XposedInterface.Chain instead of XC_MethodHook.MethodHookParam.
 *  - chain.getThisObject() replaces param.thisObject.
 *  - chain.getArg(0) replaces param.args[0].
 *  - return null (without calling chain.proceed()) replaces param.setResult(null).
 */
public class DingDingPlugin extends BasePlugin {

    private Handler mHandler;

    public DingDingPlugin(Build build) {
        super(build.mPluginManager);
        mHandler = build.mHandler;
    }

    @Override
    public void setEnable(int flag, boolean enable) {
        mHandler.setEnable(flag, enable);
    }

    @Override
    public Info getInfo() {
        return new PluginInfo(Constant.Plugin.DING_DING, Constant.Name.TITLE);
    }

    @Override
    public void onHandleLoadPackage() {

        // Bypass security guard (skip original call, return null)
        findMethod(
                M.classz.class_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl,
                M.method.method_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl_initSecurityGuard,
                Context.class)
                .before(chain -> null);

        // Hook conversation-change event to detect red packets
        findMethod(
                M.classz.class_defpackage_ConversationChangeMaid,
                M.method.method_defpackage_ConversationChangeMaid_onLatestMessageChanged,
                List.class)
                .after(chain -> {
                    mHandler.onHandlerMessage((List) chain.getArg(0));
                });

        // Hook festival red-packet pick activity
        findMethod(
                M.classz.class_android_dingtalk_redpackets_activities_FestivalRedPacketsPickActivity,
                M.method.method_android_dingtalk_redpackets_activities_FestivalRedPacketsPickActivity_initView)
                .after(chain -> {
                    mHandler.onHandlerFestivalRedPacketsPick((Activity) chain.getThisObject());
                });

        // Hook regular red-packet pick activity
        findMethod(
                M.classz.class_android_dingtalk_redpackets_activities_PickRedPacketsActivity,
                M.method.method_android_dingtalk_redpackets_activities_PickRedPacketsActivity_initView)
                .after(chain -> {
                    mHandler.onHandlerPickRedPackets((Activity) chain.getThisObject());
                });
    }

    @Override
    public void openSettings(Activity activity) {
        android.content.Intent intent = new android.content.Intent();
        intent.setClassName("com.sky.xposed.rimet",
                "com.sky.xposed.rimet.ui.activity.MainActivity");
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            android.util.Log.e("DingDingPlugin", "Failed to open settings", e);
        }
    }

    public interface Handler {

        void setEnable(int flag, boolean enable);

        void onHandlerMessage(List conversations);

        void onHandlerFestivalRedPacketsPick(Activity activity);

        void onHandlerPickRedPackets(Activity activity);
    }

    public static class Build {

        private XPluginManager mPluginManager;
        private Handler mHandler;

        public Build(XPluginManager pluginManager) {
            mPluginManager = pluginManager;
        }

        public Build setHandler(Handler handler) {
            mHandler = handler;
            return this;
        }

        public XPlugin build() {
            return new DingDingPlugin(this);
        }
    }
}
