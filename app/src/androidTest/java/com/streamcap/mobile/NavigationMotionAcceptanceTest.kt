package com.streamcap.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.streamcap.mobile.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@dagger.hilt.android.testing.HiltAndroidTest
class NavigationMotionAcceptanceTest : HiltGraphTest() {
    @get:Rule(order = Int.MIN_VALUE)
    val runtimePermissions = mainActivityRuntimePermissionsRule()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsAndDetailMoveBetweenIntermediateAndFinalFrames() {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("设置", useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(80)
            val duringTab = compose.onNodeWithText("基础设置").fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(600)
            val afterTab = compose.onNodeWithText("基础设置").fetchSemanticsNode().boundsInRoot.left
            assertTrue("Tab should visibly move, during=$duringTab after=$afterTab", duringTab > afterTab + 12)
            compose.onNode(hasText("存储管理") and hasClickAction()).performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(80)
            val duringDetail = compose.onNode(hasText("存储管理") and !hasClickAction()).fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.advanceTimeBy(600)
            val afterDetail = compose.onNode(hasText("存储管理") and !hasClickAction()).fetchSemanticsNode().boundsInRoot.left
            assertTrue("Detail should visibly move", duringDetail > afterDetail + 12)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("基础设置").assertExists()
            // Navigation state remains valid after repeated tab selection and restoration.
            repeat(3) {
                compose.onNodeWithContentDescription("视频库", useUnmergedTree = true).performClick()
                compose.mainClock.advanceTimeBy(600)
                compose.onNodeWithContentDescription("直播间", useUnmergedTree = true).performClick()
                compose.mainClock.advanceTimeBy(600)
                compose.onNodeWithContentDescription("设置", useUnmergedTree = true).performClick()
                compose.mainClock.advanceTimeBy(600)
            }
            compose.onNodeWithText("基础设置").assertExists()
            android.util.Log.i("NavigationMotionQA", "PASS tab x=$duringTab->$afterTab detail x=$duringDetail->$afterDetail; return and repeat navigation passed")
        } finally { compose.mainClock.autoAdvance = true }
    }
}
