package com.streamcap.mobile.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal fun isHuaweiDevice(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Boolean =
    manufacturer.equals("huawei", true) || brand.equals("huawei", true) || brand.equals("honor", true)

internal fun backgroundLaunchIntents(packageName: String, huawei: Boolean): List<Intent> = buildList {
    if (huawei) {
        add(Intent().setComponent(ComponentName("com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
        add(Intent().setComponent(ComponentName("com.huawei.systemmanager",
            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")))
    }
    add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    add(Intent(Settings.ACTION_SETTINGS))
}

internal fun openBackgroundLaunchSettings(context: Context) {
    for (intent in backgroundLaunchIntents(context.packageName, isHuaweiDevice())) {
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
    }
}
