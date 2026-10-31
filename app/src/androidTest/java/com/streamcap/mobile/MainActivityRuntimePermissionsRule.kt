package com.streamcap.mobile

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

internal fun mainActivityRuntimePermissionsRule(): TestRule = TestRule { base, _: Description ->
    object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val packageName = instrumentation.targetContext.packageName
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_VIDEO)
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
            base.evaluate()
        }
    }
}
