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

/**
 * Created by sky on 2019/3/12.
 */
public final class M {

    private M() {

    }

    public static final class classz {

        public static final int class_rimet_LauncherApplication = 0x1f000001;

        public static final int class_dingtalkbase_multidexsupport_DDApplication = 0x1f000002;

        public static final int class_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl = 0x1f000009;

        public static final int class_android_user_settings_activity_NewSettingActivity = 0x1f00000C;

        public static final int class_android_user_settings_activity_UserSettingsActivity = 0x1f00000D;

        /**
         * DingTalk 8.3.0+ — "OneSettingActivity" is the new unified main settings entry point,
         * replacing the older "NewSettingActivity" that no longer exists in 8.3.0.
         * ID 0x1f00000F is intentionally reserved for future use.
         */
        public static final int class_android_user_settings_activity_OneSettingActivity = 0x1f00000E;

        /** DingTalk 8.3.0 — session conversation message service (handles server push events). */
        public static final int class_rimet_biz_session_convmsg_ConvMsgService = 0x1f000010;

        /** DingTalk 8.3.0 — red-packet (HongBao) manager implementation. */
        public static final int class_rimet_biz_hbmanager_HongBaoManagerImpl = 0x1f000011;
    }

    public static final class method {

        public static final int method_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl_initSecurityGuard = 0x2f000009;

        public static final int method_android_user_settings_activity_NewSettingActivity_onCreate = 0x2f00000C;

        public static final int method_android_user_settings_activity_UserSettingsActivity_onCreate = 0x2f00000D;

        /** DingTalk 8.3.0+ — {@code onCreate} method of {@code OneSettingActivity}. */
        public static final int method_android_user_settings_activity_OneSettingActivity_onCreate = 0x2f00000E;

        /** DingTalk 8.3.0 — method in ConvMsgService that processes a server-pushed recall event. */
        public static final int method_rimet_biz_session_convmsg_ConvMsgService_onRevokeMsg = 0x2f000010;

        /** DingTalk 8.3.0 — method in HongBaoManagerImpl called when a new red packet arrives. */
        public static final int method_rimet_biz_hbmanager_HongBaoManagerImpl_onReceiveNewHb = 0x2f000011;
    }

    public static final class res {

        public static final int res_setting_msg_notice = 0x5f000003;
    }
}
