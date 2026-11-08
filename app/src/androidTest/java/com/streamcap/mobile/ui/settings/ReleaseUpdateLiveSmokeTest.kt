package com.streamcap.mobile.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseUpdateLiveSmokeTest {
    @Test
    fun checksAndDownloadsLatestPublishedApk() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass liveReleaseUpdate=true to run the network smoke test", arguments.getBoolean("liveReleaseUpdate"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val manager = AppUpdateManager(context, OkHttpClient())

        val update = manager.checkLatest("0.2.0")
        assertNotNull("The public repository must expose a stable APK update", update)
        val apk = manager.download(requireNotNull(update))
        try {
            assertTrue(apk.isFile && apk.length() > 0)
        } finally {
            apk.delete()
        }
    }
}
