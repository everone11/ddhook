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

package com.sky.xposed.rimet.data.config;

import com.sky.xposed.rimet.data.M;

/**
 * Version-specific configuration for DingTalk 8.3.0.
 *
 * <p>Class names were verified against the actual jadx decompilation of DingTalk 8.3.0
 * (see the "Analyze DingTalk APK" GitHub Actions workflow / class-names-8.3.0 artifact).</p>
 *
 * <p>Notable differences from older configs:</p>
 * <ul>
 *   <li>{@code NewSettingActivity} does <em>not</em> exist in 8.3.0.  The main settings
 *       entry-point is now {@code OneSettingActivity}.  The {@code NewSettingActivity} key
 *       is intentionally omitted so {@code SettingsPlugin} does not try to look it up;
 *       the {@code OneSettingActivity} key is provided instead.</li>
 *   <li>The {@code LightAppRuntimeReverseInterface} from the
 *       {@code com.alibaba.dingtalk.runtimebase} package is also present and contains
 *       {@code initSecurityGuard} — hooked via {@code DingTalkDeepHookPlugin}.</li>
 * </ul>
 */
public class RimetConfig830 extends RimetConfig {

    @Override
    public void loadConfig() {

        /** Class */
        add(M.classz.class_rimet_LauncherApplication,
                "com.alibaba.android.rimet.LauncherApplication");
        add(M.classz.class_dingtalkbase_multidexsupport_DDApplication,
                "com.alibaba.android.dingtalkbase.multidexsupport.DDApplication");
        add(M.classz.class_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl,
                "com.alibaba.lightapp.runtime.LightAppRuntimeReverseInterfaceImpl");

        // In 8.3.0, "NewSettingActivity" no longer exists (verified by APK decompilation).
        // "OneSettingActivity" is the new unified settings entry-point.
        // The NewSettingActivity key is intentionally NOT mapped so SettingsPlugin skips it;
        // UserSettingsActivity (confirmed present with setting_msg_notice) handles the hook.
        add(M.classz.class_android_user_settings_activity_OneSettingActivity,
                "com.alibaba.android.user.settings.activity.OneSettingActivity");
        add(M.classz.class_android_user_settings_activity_UserSettingsActivity,
                "com.alibaba.android.user.settings.activity.UserSettingsActivity");

        // DingTalk 8.3.0 — conversation message service (handles server-pushed recall events)
        add(M.classz.class_rimet_biz_session_convmsg_ConvMsgService,
                "com.alibaba.android.rimet.biz.session.convmsg.ConvMsgService");

        // DingTalk 8.3.0 — red-packet manager
        add(M.classz.class_rimet_biz_hbmanager_HongBaoManagerImpl,
                "com.alibaba.android.rimet.biz.hbmanager.HongBaoManagerImpl");

        /** Method */
        add(M.method.method_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl_initSecurityGuard,
                "initSecurityGuard");
        add(M.method.method_android_user_settings_activity_OneSettingActivity_onCreate,
                "onCreate");
        add(M.method.method_android_user_settings_activity_UserSettingsActivity_onCreate,
                "onCreate");

        // DingTalk 8.3.0 — recall-event handler in ConvMsgService
        add(M.method.method_rimet_biz_session_convmsg_ConvMsgService_onRevokeMsg,
                "onRevokeMsg");

        // DingTalk 8.3.0 — new red-packet arrival callback in HongBaoManagerImpl
        add(M.method.method_rimet_biz_hbmanager_HongBaoManagerImpl_onReceiveNewHb,
                "onReceiveNewHb");

        /** Res */
        add(M.res.res_setting_msg_notice, "setting_msg_notice");
    }
}
