package com.streamcap.mobile

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File

/** Let the public site's own scripts populate its live directory; no login or posting. */
class LiveDirectoryDiscoveryTest {
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Test fun inspectPublicDirectory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = requireNotNull(args.getString("directoryUrl"))
        require(url.startsWith("https://www.xiaohongshu.com/") || url.startsWith("https://live.kuaishou.com/"))
        val browser = withContext(Dispatchers.Main) {
            WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = DESKTOP_USER_AGENT
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                layout(0, 0, 1280, 720)
                loadUrl(url)
            }
        }
        try {
            delay(25_000)
            val result = CompletableDeferred<String>()
            withContext(Dispatchers.Main) {
                browser.evaluateJavascript("""JSON.stringify({links:Array.from(document.querySelectorAll('a[href]')).map(a=>a.href).filter(u=>/livestream\/|\/u\//.test(u)).slice(0,100),state:window.__INITIAL_STATE__&&window.__INITIAL_STATE__.liveList,body:document.body.innerText.slice(0,1500)})""") { result.complete(it) }
            }
            File(instrumentation.targetContext.filesDir, "directory-discovery.json").writeText(withTimeout(10_000) { result.await() })
        } finally { withContext(Dispatchers.Main) { browser.stopLoading(); browser.destroy() } }
    }
}
