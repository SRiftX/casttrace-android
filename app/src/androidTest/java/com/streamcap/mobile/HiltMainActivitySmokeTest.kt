package com.streamcap.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.streamcap.mobile.app.MainActivity
import org.junit.Rule
import org.junit.Test

@dagger.hilt.android.testing.HiltAndroidTest
class HiltMainActivitySmokeTest : HiltGraphTest() {
    @get:Rule(order = Int.MIN_VALUE + 1)
    val runtimePermissions = mainActivityRuntimePermissionsRule()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hiltInjectedActivityRendersComposeHome() {
        compose.onNodeWithText("CastTrace", useUnmergedTree = true).assertExists()
    }
}
