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
import android.util.Log;
import android.view.View;

import com.sky.xposed.rimet.Constant;
import com.sky.xposed.rimet.data.M;
import com.sky.xposed.rimet.plugin.base.BaseHandler;
import com.sky.xposed.rimet.plugin.interfaces.XConfigManager;
import com.sky.xposed.rimet.plugin.interfaces.XPluginManager;
import com.sky.xposed.rimet.util.CollectionUtil;

import java.util.List;
import java.util.Map;

/**
 * Created by sky on 2019/3/15.
 *
 * Adapted for libxposed API:
 *  - XposedHelpers.callMethod() replaced with BaseHandler.callMethod() (reflection).
 *  - XposedHelpers.getObjectField() replaced with BaseHandler.getObjectField() (reflection).
 *  - XposedHelpers.getIntField() replaced with BaseHandler.getIntField() (reflection).
 *  - XposedHelpers.callStaticMethod() replaced with BaseHandler.callStaticMethod() (reflection).
 *  - XposedHelpers.newInstance() replaced with BaseHandler.newInstance() (reflection).
 *  - ConversionUtil.parseInt/parseLong replaced with BaseHandler.parseInt/parseLong.
 *  - ResourceUtil.getId() replaced with BaseHandler.getViewId().
 */
public class DingDingHandler extends BaseHandler implements DingDingPlugin.Handler {

    private static final String TAG = "DingDingHandler";

    private XConfigManager mXConfigManager;
    private boolean mEnableLucky;
    private boolean mEnableFastLucky;

    public DingDingHandler(XPluginManager pluginManager) {
        super(pluginManager);
        mXConfigManager = getPluginManager().getConfigManager();
        mEnableLucky = mXConfigManager.getBoolean(Constant.XFlag.ENABLE_LUCKY, true);
        mEnableFastLucky = mXConfigManager.getBoolean(Constant.XFlag.ENABLE_FAST_LUCKY, true);
    }

    @Override
    public void onHandlerMessage(List conversations) {

        if (!mEnableLucky) return;

        for (Object conversation : conversations) {
            onHandlerMessage(conversation);
        }
    }

    @Override
    public void onHandlerFestivalRedPacketsPick(Activity activity) {

        if (!mEnableFastLucky) return;

        View view = activity.findViewById(getViewId(activity, getXString(M.res.res_iv_pick)));
        if (view != null && view.isClickable()) view.performClick();
    }

    @Override
    public void onHandlerPickRedPackets(Activity activity) {

        if (!mEnableFastLucky) return;

        View view = activity.findViewById(getViewId(activity, getXString(M.res.res_btn_pick)));
        if (view != null && view.isClickable()) view.performClick();
    }

    @Override
    public void setEnable(int flag, boolean enable) {

        switch (flag) {
            case Constant.XFlag.ENABLE_LUCKY:
                mEnableLucky = enable;
                break;
            case Constant.XFlag.ENABLE_FAST_LUCKY:
                mEnableFastLucky = enable;
                break;
        }
    }

    /**
     * 处理消息 — detects red-packet messages and triggers auto-pick.
     */
    @SuppressWarnings("unchecked")
    private void onHandlerMessage(Object conversation) {

        Object message = callMethod(conversation,
                getXString(M.method.method_wukong_im_conversation_ConversationImpl_latestMessage));
        if (message == null) return;

        Object messageContent = callMethod(message,
                getXString(M.method.method_wukong_im_message_MessageImpl_messageContent));
        if (messageContent == null) return;

        int type = getIntField(messageContent,
                getXString(M.field.field_wukong_im_message_MessageContentImpl_mType));

        if (902 != type) return;

        List messageContents = (List) callMethod(messageContent,
                getXString(M.method.method_wukong_im_message_MessageContentImpl_contents));

        if (CollectionUtil.isEmpty(messageContents)) return;

        Object customMessage = messageContents.get(0);
        int customType = getIntField(customMessage,
                getXString(M.field.field_wukong_im_message_MessageContentImpl_CustomMessageContentImpl_mCustomType));

        if (902 != customType) return;

        Map<String, String> extension = (Map<String, String>) getObjectField(customMessage,
                getXString(M.field.field_wukong_im_message_MessageContentImpl_CustomMessageContentImpl_mExtension));

        if (extension == null) return;

        String sid = extension.get(getXString(M.key.key_sid));
        String clusterId = extension.get(getXString(M.key.key_clusterid));

        // 获取休眠的时间
        long delayMillis = 1000L * parseInt(
                mXConfigManager.getString(Constant.XFlag.LUCKY_DELAYED, ""));

        postDelayed(() -> pickRedEnvelop(parseLong(sid), clusterId), delayMillis);
    }

    /**
     * 接收红包 — invokes the DingTalk red-packet pick service via reflection.
     */
    private void pickRedEnvelop(long sid, String clusterId) {

        Class<?> classServiceFactory = findClass(M.classz.class_defpackage_ServiceFactory);
        Class<?> classRedEnvelopPickIService = findClass(
                M.classz.class_android_dingtalk_redpackets_idl_service_RedEnvelopPickIService);
        Class<?> classRedPacketsRpc = findClass(M.classz.class_defpackage_RedPacketsRpc);
        Class<?> classSubRedPacketsRpc = findClass(M.classz.class_defpackage_RedPacketsRpc_9);

        if (classServiceFactory == null || classRedEnvelopPickIService == null
                || classRedPacketsRpc == null || classSubRedPacketsRpc == null) {
            Log.w(TAG, "pickRedEnvelop: one or more required classes not found");
            return;
        }

        Object redPacketsRpc = callStaticMethod(classRedPacketsRpc,
                getXString(M.method.method_defpackage_RedPacketsRpc_newInstance));

        Object handler = newInstance(classSubRedPacketsRpc, redPacketsRpc, null);

        Object redEnvelopPickIService = callStaticMethod(classServiceFactory,
                getXString(M.method.method_defpackage_ServiceFactory_getService),
                classRedEnvelopPickIService);

        if (redEnvelopPickIService == null) {
            Log.w(TAG, "pickRedEnvelop: RedEnvelopPickIService is null");
            return;
        }

        callMethod(redEnvelopPickIService,
                getXString(M.method.method_android_dingtalk_redpackets_idl_service_RedEnvelopPickIService_pickRedEnvelopCluster),
                sid, clusterId, handler);
    }
}
