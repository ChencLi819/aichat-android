package com.jev.probe.core

import android.os.Build

/**
 * Which vendor ROM this phone runs, when it is one that kills background
 * accessibility services.
 *
 * Chinese ROMs (Honor/Huawei, Xiaomi, OPPO, vivo) freeze or kill a background
 * service unless the user whitelists the app by hand, and each one hides that
 * switch in a different menu. A generic "turn on autostart" line is not enough —
 * the setup screen has to name the menu that actually exists on this phone.
 *
 * Detection is by manufacturer/brand string only. It is a hint for wording and
 * for which settings page to try opening; every screen must still work when the
 * guess is wrong, so nothing here is ever a hard requirement.
 */
enum class Rom(
    /** Shown in the setup screen's row title. */
    val label: String,
    /** The exact menu path to walk the user through, brand by brand. */
    val steps: String,
    /** Vendor power/startup-manager packages to try opening, best first. */
    val managerPackages: List<String>
) {
    HONOR(
        label = "荣耀 MagicOS",
        steps = "设置 → 应用 → 应用启动管理 → 本应用 → 关闭「自动管理」，" +
            "把「允许自启动」「允许关联启动」「允许后台活动」都打开；" +
            "再到 设置 → 电池 → 更多电池设置，关闭「智能省电」或把本应用设为「允许后台活动」",
        managerPackages = listOf("com.hihonor.systemmanager", "com.hihonor.powergenie")
    ),
    HUAWEI(
        label = "华为 HarmonyOS",
        steps = "设置 → 应用 → 应用启动管理 → 本应用 → 关闭「自动管理」，" +
            "把「允许自启动」「允许关联启动」「允许后台活动」都打开；" +
            "再到 设置 → 电池 → 更多电池设置，关闭「智能省电」",
        managerPackages = listOf("com.huawei.systemmanager")
    ),
    XIAOMI(
        label = "小米 HyperOS",
        steps = "设置 → 应用设置 → 应用管理 → 本应用 → 自启动 打开；" +
            "再到 设置 → 省电与电池 → 本应用 → 无限制（关掉省电策略）",
        managerPackages = listOf("com.miui.securitycenter", "com.miui.powerkeeper")
    ),
    OPPO(
        label = "OPPO / 一加 ColorOS",
        steps = "设置 → 应用 → 自启动管理 → 允许本应用自启动；" +
            "再到 设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台运行",
        managerPackages = listOf("com.coloros.safecenter", "com.oplus.battery")
    ),
    VIVO(
        label = "vivo OriginOS",
        steps = "设置 → 应用与权限 → 权限管理 → 自启动 → 允许本应用；" +
            "再到 设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电",
        managerPackages = listOf("com.vivo.permissionmanager", "com.iqoo.secure")
    ),
    OTHER(
        label = "其他机型",
        steps = "在系统设置里把本应用加入「自启动」白名单，并把电池策略设为「无限制」，" +
            "否则后台服务会被冻结，读不到消息",
        managerPackages = emptyList()
    );

    companion object {

        /** The ROM this build is running on, or [OTHER] when it is none of them. */
        fun current(): Rom {
            val id = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
            return when {
                id.contains("honor") -> HONOR
                id.contains("huawei") -> HUAWEI
                id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> XIAOMI
                id.contains("oppo") || id.contains("oneplus") || id.contains("realme") -> OPPO
                id.contains("vivo") || id.contains("iqoo") -> VIVO
                else -> OTHER
            }
        }
    }
}
