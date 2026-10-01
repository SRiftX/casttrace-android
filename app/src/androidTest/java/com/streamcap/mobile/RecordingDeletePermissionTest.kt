package com.streamcap.mobile

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.data.database.toDomain
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.ui.recordings.RecordingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RecordingDeletePermissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun foreignMediaConsentCanBeCancelledThenApproved(): Unit = runBlocking {
        val context = compose.activity
        val container = (context.application as StreamCapApplication).container
        val repository = container.repository
        val raw = requireNotNull(InstrumentationRegistry.getArguments().getString("foreignMediaUri")) {
            "Create a disposable shell-owned MediaStore video and supply foreignMediaUri"
        }
        val uri = Uri.parse(raw)
        val roomId = container.database.liveRoomDao().insert(LiveRoomEntity(
            originalUrl = "https://qa.invalid/delete", normalizedUrl = "https://qa.invalid/delete",
            platform = "qaDelete", anchorName = "QA删除权限", roomTitle = null))
        val temporary = File.createTempFile("qa-delete-retain-", ".ts.part", context.cacheDir).apply { writeText("recovery") }
        val entity = RecordingSessionEntity(roomId = roomId, roomName = "QA删除权限", status = TaskStatus.COMPLETED,
            outputUri = raw, outputUris = listOf(raw), temporaryPath = temporary.absolutePath)
        val id = container.database.recordingSessionDao().insert(entity)
        val viewModel = RecordingsViewModel(repository, container)
        try {
            val cancelled = CompletableDeferred<String?>()
            viewModel.delete(entity.copy(id = id).toDomain()) { cancelled.complete(it) }
            val request = withTimeout(15_000) { viewModel.deleteAuthorization.first { it != null }!! }
            assertNotNull("Foreign media must request system consent", request.sender)
            compose.runOnUiThread { context.startIntentSenderForResult(request.sender!!, 9021, null, 0, 0, 0) }
            delay(1_000)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            viewModel.completeDeleteAuthorization(false)
            assertNotNull(withTimeout(10_000) { cancelled.await() })
            assertNotNull(repository.session(id))
            assertTrue(temporary.exists())
            assertTrue(context.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() })

            val approved = CompletableDeferred<String?>()
            viewModel.delete(entity.copy(id = id).toDomain()) { approved.complete(it) }
            val retry = withTimeout(15_000) { viewModel.deleteAuthorization.first { it != null }!! }
            compose.runOnUiThread { context.startIntentSenderForResult(retry.sender!!, 9022, null, 0, 0, 0) }
            withTimeout(10_000) {
                while (!clickSystemConsent()) delay(100)
            }
            withTimeout(10_000) {
                while (context.contentResolver.query(uri, arrayOf("_id"), null, null, null)!!.use { it.moveToFirst() }) delay(100)
            }
            viewModel.completeDeleteAuthorization(true)
            assertNull(withTimeout(10_000) { approved.await() })
            assertNull(repository.session(id))
            assertFalse(temporary.exists())
            assertTrue(container.recordingStorage.deleteRecordingFiles(listOf(raw)).successful)
            android.util.Log.i("DeletePermissionQA", "PASS actual system consent: cancel preserves record/source; approve deletes foreign media, source and record; repeated delete succeeds")
        } finally {
            viewModel.completeDeleteAuthorization(false)
            repository.session(id)?.let { repository.deleteSession(it) }
            repository.deleteRoom(roomId)
            temporary.delete()
        }
    }

    private fun clickSystemConsent(): Boolean {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun click(node: AccessibilityNodeInfo): Boolean {
            val systemConsent = node.packageName?.toString()?.startsWith("com.android.providers.media") == true
            val approve = node.viewIdResourceName == "android:id/button1" ||
                node.viewIdResourceName?.endsWith(":id/permission_allow_button") == true ||
                node.text?.toString() in setOf("允许", "删除", "Allow", "Delete", "ALLOW", "DELETE")
            if (systemConsent && approve && node.isClickable) {
                android.util.Log.i("DeletePermissionQA", "System consent button=${node.text}")
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (systemConsent && node.text != null) android.util.Log.i("DeletePermissionQA", "node id=${node.viewIdResourceName} text=${node.text} clickable=${node.isClickable}")
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::click) == true }
        }
        val displays = automation.windowsOnAllDisplays
        return (0 until displays.size()).any { index -> displays.valueAt(index).any { window -> window.root?.let(::click) == true } }
    }
}
