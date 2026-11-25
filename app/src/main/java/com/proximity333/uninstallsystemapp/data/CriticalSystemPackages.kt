package com.proximity333.uninstallsystemapp.data

import com.proximity333.uninstallsystemapp.BuildConfig

/**
 * Exact package names protected from destructive operations (uninstall / disable).
 *
 * Removing or disabling these packages causes bootloops, lockscreen/SystemUI failure, broken
 * telephony/Settings/installer/permission control, loss of the sole launcher/WebView, or breaks
 * Shizuku/ADB recovery and this app itself.
 */
object CriticalSystemPackages {
    /** Ordered backing list exposed for duplicate-detection unit tests. */
    val entries: List<String> = listOf(
        // AOSP, Android Mainline, and Google variants
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.mms.service",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.networkstack",
        "com.android.networkstack.inprocess",
        "com.google.android.networkstack",
        "com.android.externalstorage",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.se",

        // Xiaomi, Redmi, and POCO
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.miui.securitycenter",
        "com.xiaomi.finddevice",
        "com.lbe.security.miui",
        "com.miui.packageinstaller",

        // OPPO, OnePlus, and realme
        "com.android.launcher",
        "com.oppo.launcher",
        "net.oneplus.launcher",

        // vivo and iQOO
        "com.bbk.launcher2",

        // Huawei and HONOR
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.huawei.webview",

        // Meizu, nubia / REDMAGIC, and Samsung
        "com.meizu.flyme.launcher",
        "cn.nubia.launcher",
        "com.sec.android.app.launcher",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",

        // Tool self and privilege/recovery dependencies
        "com.android.shell",
        "moe.shizuku.privileged.api",
        BuildConfig.APPLICATION_ID,
    )

    /** Case-sensitive exact package set checked by mutation guards. */
    val names: Set<String> = entries.toSet()
}