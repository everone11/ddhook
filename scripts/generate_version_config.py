#!/usr/bin/env python3
"""
Generate a RimetConfig<NormalizedVersion>.java from the class-names report
produced by the "Analyze DingTalk APK" workflow, update VersionManager.java,
and add any newly discovered candidate classes to DingTalkDeepHookPlugin.java.

Usage:
  python3 scripts/generate_version_config.py <report_file> <version_name>

Example:
  python3 scripts/generate_version_config.py /tmp/class_names_7.5.0.txt 7.5.0
"""

import os
import re
import sys

# ---------------------------------------------------------------------------
# Paths (relative to repository root)
# ---------------------------------------------------------------------------
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_DIR = os.path.join(
    REPO_ROOT,
    "app/src/main/java/com/sky/xposed/rimet/data/config",
)
VERSION_MANAGER_PATH = os.path.join(
    REPO_ROOT,
    "app/src/main/java/com/sky/xposed/rimet/data/VersionManager.java",
)
DEEP_HOOK_PATH = os.path.join(
    REPO_ROOT,
    "app/src/main/java/com/sky/xposed/rimet/plugin/system/DingTalkDeepHookPlugin.java",
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def normalize_version(version_name: str) -> str:
    """'7.5.0' -> '750', '8.3.0' -> '830', '4.6.17' -> '4617'"""
    return version_name.replace(".", "")


def is_real_class(name: str) -> bool:
    """Reject obfuscated / defpackage class names that contain no dot or start with 'defpackage.'"""
    return "." in name and not name.startswith("defpackage.")


def parse_section(content: str, header: str) -> list:
    """Return non-empty, stripped lines between '### <header>' and the next '### ' heading."""
    start = content.find(f"### {header}")
    if start == -1:
        return []
    start = content.find("\n", start) + 1
    end = content.find("\n### ", start)
    block = content[start: (end if end != -1 else len(content))]
    return [line.strip() for line in block.splitlines() if line.strip()]


def extract_class_from_method_line(line: str) -> str:
    """
    Turn a line like
        'com/alibaba/.../ConvMsgService.java:42:    public void onRevokeMsg(…)'
    into 'com.alibaba.....ConvMsgService'.
    Returns '' if the line doesn't look like a source reference.
    """
    # The sed in analyze-apk.yml strips the '/tmp/jadx-out/sources/' prefix already.
    m = re.match(r"([^\s:]+\.java):", line)
    if not m:
        return ""
    java_path = m.group(1)  # e.g. com/alibaba/android/.../ConvMsgService.java
    return java_path[: -len(".java")].replace("/", ".")


# ---------------------------------------------------------------------------
# Report parsing
# ---------------------------------------------------------------------------

def parse_report(report_path: str) -> dict:
    with open(report_path, encoding="utf-8") as fh:
        content = fh.read()

    info: dict = {}

    # ── LauncherApplication ─────────────────────────────────────────────────
    launchers = [c for c in parse_section(content, "LauncherApplication candidates")
                 if is_real_class(c)]
    info["launcher_app"] = next(
        (c for c in launchers if c.endswith("rimet.LauncherApplication")),
        "com.alibaba.android.rimet.LauncherApplication",
    )

    # ── DDApplication ────────────────────────────────────────────────────────
    dd_apps = [c for c in parse_section(content, "DDApplication candidates")
               if is_real_class(c)]
    info["dd_app"] = next(
        (c for c in dd_apps if c.endswith("DDApplication")),
        "com.alibaba.android.dingtalkbase.multidexsupport.DDApplication",
    )

    # ── LightAppRuntimeReverseInterfaceImpl ──────────────────────────────────
    lightapps = [c for c in parse_section(content, "LightAppRuntime candidates")
                 if is_real_class(c) and "$$" not in c]
    # Prefer com.alibaba.lightapp.runtime (not runtimebase)
    info["lightapp_impl"] = next(
        (c for c in lightapps
         if "lightapp.runtime" in c and c.endswith("LightAppRuntimeReverseInterfaceImpl")),
        next(
            (c for c in lightapps if c.endswith("LightAppRuntimeReverseInterfaceImpl")),
            "com.alibaba.lightapp.runtime.LightAppRuntimeReverseInterfaceImpl",
        ),
    )

    # ── Settings activity ────────────────────────────────────────────────────
    user_settings_lines = [c for c in parse_section(
        content, "UserSettingsActivity / OneSettingActivity candidates")
        if is_real_class(c)]

    one_setting = next(
        (c for c in user_settings_lines
         if c.endswith("OneSettingActivity") and "alibaba.android.user.settings" in c),
        None,
    )
    new_setting_lines = [c for c in parse_section(content, "NewSettingActivity candidates")
                         if is_real_class(c)]
    new_setting = next(
        (c for c in new_setting_lines
         if c.endswith("NewSettingActivity") and "alibaba.android.user.settings" in c),
        None,
    )

    if one_setting:
        info["setting_type"] = "One"
        info["setting_class"] = one_setting
    elif new_setting:
        info["setting_type"] = "New"
        info["setting_class"] = new_setting
    else:
        info["setting_type"] = None
        info["setting_class"] = None

    info["user_settings_activity"] = next(
        (c for c in user_settings_lines
         if c.endswith("UserSettingsActivity") and "alibaba.android.user.settings" in c),
        "com.alibaba.android.user.settings.activity.UserSettingsActivity",
    )

    # ── Anti-recall candidates ───────────────────────────────────────────────
    recall_classes_direct = [c for c in parse_section(
        content, "Anti-recall: message revoke / recall classes")
        if is_real_class(c)]

    # Also extract class names inferred from method-reference lines
    recall_from_methods = [
        extract_class_from_method_line(line)
        for line in parse_section(content, "Anti-recall: onRevokeMsg / revokeMsg methods")
    ]
    recall_from_methods = [c for c in recall_from_methods if is_real_class(c)]

    all_recall = list(dict.fromkeys(recall_classes_direct + recall_from_methods))  # dedup, order-preserving

    info["recall_class"] = next(
        (c for c in all_recall if "ConvMsgService" in c),
        all_recall[0] if all_recall else None,
    )
    info["all_recall_classes"] = all_recall

    # ── Red-packet candidates ────────────────────────────────────────────────
    hongbao_classes_direct = [c for c in parse_section(
        content, "Red-packet: HongBao / HB manager classes")
        if is_real_class(c)]

    hongbao_from_methods = [
        extract_class_from_method_line(line)
        for line in parse_section(
            content,
            "Red-packet: onReceiveNewHb / onReceiveHongBao / openHongBao methods")
    ]
    hongbao_from_methods = [c for c in hongbao_from_methods if is_real_class(c)]

    all_hongbao = list(dict.fromkeys(hongbao_classes_direct + hongbao_from_methods))

    info["hongbao_class"] = next(
        (c for c in all_hongbao if "HongBaoManagerImpl" in c),
        all_hongbao[0] if all_hongbao else None,
    )
    info["all_hongbao_classes"] = all_hongbao

    return info


# ---------------------------------------------------------------------------
# Java source generation
# ---------------------------------------------------------------------------

_LICENSE = """\
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
 */"""


def generate_config_java(version_name: str, normalized: str, info: dict) -> str:
    lines = [_LICENSE, ""]
    lines += [
        "package com.sky.xposed.rimet.data.config;",
        "",
        "import com.sky.xposed.rimet.data.M;",
        "",
        "/**",
        f" * Version-specific configuration for DingTalk {version_name}.",
        f" * Auto-generated by scripts/generate_version_config.py from APK decompilation.",
        " */",
        f"public class RimetConfig{normalized} extends RimetConfig {{",
        "",
        "    @Override",
        "    public void loadConfig() {",
        "",
        "        /** Class */",
        f'        add(M.classz.class_rimet_LauncherApplication,',
        f'                "{info["launcher_app"]}");',
        f'        add(M.classz.class_dingtalkbase_multidexsupport_DDApplication,',
        f'                "{info["dd_app"]}");',
        f'        add(M.classz.class_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl,',
        f'                "{info["lightapp_impl"]}");',
    ]

    setting_type = info.get("setting_type")
    setting_class = info.get("setting_class")
    if setting_type == "One" and setting_class:
        lines += [
            f'        add(M.classz.class_android_user_settings_activity_OneSettingActivity,',
            f'                "{setting_class}");',
        ]
    elif setting_type == "New" and setting_class:
        lines += [
            f'        add(M.classz.class_android_user_settings_activity_NewSettingActivity,',
            f'                "{setting_class}");',
        ]

    if info.get("user_settings_activity"):
        lines += [
            f'        add(M.classz.class_android_user_settings_activity_UserSettingsActivity,',
            f'                "{info["user_settings_activity"]}");',
        ]

    if info.get("recall_class"):
        lines += [
            f'        add(M.classz.class_rimet_biz_session_convmsg_ConvMsgService,',
            f'                "{info["recall_class"]}");',
        ]

    if info.get("hongbao_class"):
        lines += [
            f'        add(M.classz.class_rimet_biz_hbmanager_HongBaoManagerImpl,',
            f'                "{info["hongbao_class"]}");',
        ]

    lines += ["", "        /** Method */"]
    lines += [
        f'        add(M.method.method_lightapp_runtime_LightAppRuntimeReverseInterfaceImpl_initSecurityGuard,',
        f'                "initSecurityGuard");',
    ]

    if setting_type == "One":
        lines += [
            f'        add(M.method.method_android_user_settings_activity_OneSettingActivity_onCreate,',
            f'                "onCreate");',
        ]
    elif setting_type == "New":
        lines += [
            f'        add(M.method.method_android_user_settings_activity_NewSettingActivity_onCreate,',
            f'                "onCreate");',
        ]

    lines += [
        f'        add(M.method.method_android_user_settings_activity_UserSettingsActivity_onCreate,',
        f'                "onCreate");',
    ]

    if info.get("recall_class"):
        lines += [
            f'        add(M.method.method_rimet_biz_session_convmsg_ConvMsgService_onRevokeMsg,',
            f'                "onRevokeMsg");',
        ]

    if info.get("hongbao_class"):
        lines += [
            f'        add(M.method.method_rimet_biz_hbmanager_HongBaoManagerImpl_onReceiveNewHb,',
            f'                "onReceiveNewHb");',
        ]

    lines += [
        "",
        "        /** Res */",
        '        add(M.res.res_setting_msg_notice, "setting_msg_notice");',
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# VersionManager.java update
# ---------------------------------------------------------------------------

def update_version_manager(version_name: str, normalized: str) -> bool:
    """
    Ensure VersionManager.java maps <version_name> to RimetConfig<normalized>.
    - Adds the import if missing.
    - If the version is already mapped to a *different* config class, replaces
      that line.  If already mapped to the correct class, skips.
    Returns True if the file was modified.
    """
    with open(VERSION_MANAGER_PATH, encoding="utf-8") as fh:
        content = fh.read()

    changed = False
    new_class = f"RimetConfig{normalized}"
    desired_entry = f'CONFIG_MAP.put("{version_name}", {new_class}.class);'

    # ── Import ────────────────────────────────────────────────────────────────
    import_line = f"import com.sky.xposed.rimet.data.config.{new_class};"
    if import_line not in content:
        last = list(re.finditer(
            r"import com\.sky\.xposed\.rimet\.data\.config\.RimetConfig\w+;",
            content,
        ))
        if last:
            pos = last[-1].end()
            content = content[:pos] + f"\n{import_line}" + content[pos:]
            changed = True

    # ── CONFIG_MAP entry ──────────────────────────────────────────────────────
    if desired_entry in content:
        print(f"[VersionManager] {version_name} already mapped to {new_class} — skipped")
    else:
        # Check whether the version is already mapped to a different config
        existing = re.search(
            rf'CONFIG_MAP\.put\("{re.escape(version_name)}",\s*\w+\.class\);',
            content,
        )
        if existing:
            # Replace the old mapping with the new one
            content = content[: existing.start()] + desired_entry + content[existing.end():]
            print(f"[VersionManager] replaced {version_name} mapping -> {new_class}")
        else:
            # Append after the last CONFIG_MAP.put line
            last_put = list(re.finditer(
                r'CONFIG_MAP\.put\("[^"]+",\s*RimetConfig\w+\.class\);',
                content,
            ))
            if last_put:
                pos = last_put[-1].end()
                content = content[:pos] + f"\n        {desired_entry}" + content[pos:]
                print(f"[VersionManager] registered {version_name} -> {new_class}")
            else:
                print(f"[VersionManager] WARNING: could not locate CONFIG_MAP.put anchor")
                return False
        changed = True

    if changed:
        with open(VERSION_MANAGER_PATH, "w", encoding="utf-8") as fh:
            fh.write(content)

    return changed


# ---------------------------------------------------------------------------
# DingTalkDeepHookPlugin.java update (candidate arrays)
# ---------------------------------------------------------------------------

def _insert_before_array_close(content: str, array_name: str, new_classes: list) -> str:
    """
    Append `new_classes` entries into the named String[] array, just before its closing '};'.
    """
    anchor = f"String[] {array_name} = {{"
    idx = content.find(anchor)
    if idx == -1:
        print(f"[DeepHookPlugin] array '{array_name}' not found — skipping")
        return content

    close_idx = content.find("};", idx)
    if close_idx == -1:
        return content

    insertion = "".join(
        f'            "{cls}", // Auto-added from APK analysis\n'
        for cls in new_classes
    )
    return content[:close_idx] + insertion + "    " + content[close_idx:]


def update_deep_hook_plugin(all_recall: list, all_hongbao: list) -> bool:
    """
    Append newly discovered recall / hongbao candidate class names to the
    respective String[] arrays. Returns True if the file was modified.
    """
    with open(DEEP_HOOK_PATH, encoding="utf-8") as fh:
        content = fh.read()

    changed = False

    # ── RECALL_CLASS_CANDIDATES ───────────────────────────────────────────────
    new_recall = [c for c in all_recall
                  if is_real_class(c) and f'"{c}"' not in content]
    if new_recall:
        content = _insert_before_array_close(content, "RECALL_CLASS_CANDIDATES", new_recall)
        changed = True
        for c in new_recall:
            print(f"[DeepHookPlugin] added recall candidate: {c}")

    # ── HONGBAO_CLASS_CANDIDATES ──────────────────────────────────────────────
    new_hongbao = [c for c in all_hongbao
                   if is_real_class(c) and f'"{c}"' not in content]
    if new_hongbao:
        content = _insert_before_array_close(content, "HONGBAO_CLASS_CANDIDATES", new_hongbao)
        changed = True
        for c in new_hongbao:
            print(f"[DeepHookPlugin] added hongbao candidate: {c}")

    if changed:
        with open(DEEP_HOOK_PATH, "w", encoding="utf-8") as fh:
            fh.write(content)
        print("[DeepHookPlugin] updated with new candidates")
    else:
        print("[DeepHookPlugin] no new candidates found — skipped")

    return changed


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <report_file> <version_name>")
        sys.exit(1)

    report_path = sys.argv[1]
    version_name = sys.argv[2]
    normalized = normalize_version(version_name)

    print(f"=== Auto-adapting DingTalk {version_name} (class suffix: {normalized}) ===")

    # ── Parse the class-names report ─────────────────────────────────────────
    info = parse_report(report_path)
    print(f"[report] launcher_app         : {info['launcher_app']}")
    print(f"[report] dd_app               : {info['dd_app']}")
    print(f"[report] lightapp_impl        : {info['lightapp_impl']}")
    print(f"[report] setting_type/class   : {info['setting_type']} / {info['setting_class']}")
    print(f"[report] user_settings_activity: {info['user_settings_activity']}")
    print(f"[report] recall_class         : {info['recall_class']}")
    print(f"[report] all_recall_classes   : {info['all_recall_classes']}")
    print(f"[report] hongbao_class        : {info['hongbao_class']}")
    print(f"[report] all_hongbao_classes  : {info['all_hongbao_classes']}")

    # ── Generate RimetConfig<normalized>.java ─────────────────────────────────
    config_path = os.path.join(CONFIG_DIR, f"RimetConfig{normalized}.java")
    java_content = generate_config_java(version_name, normalized, info)
    with open(config_path, "w", encoding="utf-8") as fh:
        fh.write(java_content)
    print(f"[config] wrote {config_path}")

    # ── Update VersionManager.java ────────────────────────────────────────────
    update_version_manager(version_name, normalized)

    # ── Update DingTalkDeepHookPlugin.java with new candidates ────────────────
    update_deep_hook_plugin(info["all_recall_classes"], info["all_hongbao_classes"])

    print("=== Done ===")


if __name__ == "__main__":
    main()
