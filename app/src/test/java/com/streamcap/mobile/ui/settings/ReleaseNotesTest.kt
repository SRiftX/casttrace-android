package com.streamcap.mobile.ui.settings

import com.streamcap.mobile.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun releaseNotesVersionMatchesInstalledApplicationVersion() {
        assertEquals(BuildConfig.VERSION_NAME, ReleaseNotes.VERSION)
    }

    @Test
    fun releaseNotesCoverTheDocumentedUserFacingChanges() {
        val content = ReleaseNotes.changes.joinToString(" ")

        assertTrue(content.contains("完整平台目录"))
        assertTrue(content.contains("使用说明"))
        assertTrue(content.contains("自动更新"))
        assertTrue(content.contains("电池优化"))
        assertTrue(content.contains("日志"))
    }
}
