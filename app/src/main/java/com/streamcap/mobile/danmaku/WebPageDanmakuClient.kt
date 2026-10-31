package com.streamcap.mobile.danmaku

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Experimental receive-only fallback for web-managed chat handshakes. No posting API is exposed. */
class WebPageDanmakuClient(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val platform: String,
) : DanmakuClient {
    private var view: WebView? = null
    @Volatile private var closeCapture: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override suspend fun connect(context: DanmakuConnectContext): Flow<DanmakuFrame> = callbackFlow {
        val address = requireNotNull(context.roomUrl.toHttpUrlOrNull())
        require(address.scheme == "https" && allowedHost(address.host)) { "聊天页面地址无效" }
        val bridgeName = "ChatCapture" + UUID.randomUUID().toString().replace("-", "")
        val script = captureScript(platform, bridgeName)
        val client = httpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        val bridge = object {
            @JavascriptInterface fun post(payload: String) {
                if (payload.length > 65_536) return
                runCatching {
                    val item = JSONObject(payload)
                    if (item.optString("state") == "observing") {
                        trySend(DanmakuFrame.Status("observing-web-chat"))
                    } else parseCaptured(platform, item, context, android.os.SystemClock.elapsedRealtime())?.let { frame ->
                        if (trySend(frame).isFailure) close(java.io.IOException("弹幕接收队列已满"))
                    }
                }
            }
        }
        val webView = withContext(Dispatchers.Main) {
            WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = DESKTOP_USER_AGENT
                settings.mediaPlaybackRequiresUserGesture = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(bridge, bridgeName)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && !(request.url.scheme == "https" && allowedHost(request.url.host.orEmpty()))

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString().toHttpUrlOrNull() ?: return null
                        // The page is only needed for chat. Recording owns the media connection.
                        if (url.encodedPath.lowercase().let { it.endsWith(".flv") || it.endsWith(".m3u8") || it.endsWith(".ts") })
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        if (!request.isForMainFrame || !allowedHost(url.host) || url.scheme != "https") return null
                        return runCatching {
                            val builder = Request.Builder().url(url).header("User-Agent", DESKTOP_USER_AGENT)
                            val cookies = context.cookie?.takeIf(String::isNotBlank)
                                ?: android.webkit.CookieManager.getInstance().getCookie(url.toString())
                            cookies?.let { builder.header("Cookie", it) }
                            client.newCall(builder.build()).execute().use { response ->
                                check(response.isSuccessful && allowedHost(response.request.url.host))
                                val html = response.body?.string().orEmpty()
                                require(html.length <= 8 * 1024 * 1024)
                                val prefix = "<script>$script</script>"
                                val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
                                val injected = if (head == null) prefix + html else
                                    html.substring(0, head.range.last + 1) + prefix + html.substring(head.range.last + 1)
                                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)))
                            }
                        }.getOrElse {
                            trySend(DanmakuFrame.Status("web-chat-page-error"))
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (url.toHttpUrlOrNull()?.host?.let(::allowedHost) == true) view.evaluateJavascript(script, null)
                    }
                }
                layout(0, 0, 1280, 720)
                loadUrl(context.roomUrl)
            }
        }
        view = webView
        val stopCapture: () -> Unit = { close(); Unit }
        closeCapture = stopCapture
        trySend(DanmakuFrame.Status("connecting-web-chat"))
        awaitClose {
            if (closeCapture === stopCapture) closeCapture = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                webView.stopLoading(); webView.removeJavascriptInterface(bridgeName); webView.destroy()
                if (view === webView) view = null
            }
        }
    }

    override suspend fun close(reason: String) { closeCapture?.invoke() }

    private fun allowedHost(host: String): Boolean = when (platform) {
        "yy" -> host == "yy.com" || host.endsWith(".yy.com")
        "rednote" -> listOf("xiaohongshu.com", "xhslink.com", "app.xhs.cn").any { host == it || host.endsWith(".$it") }
        else -> false
    }

    companion object {
        internal fun parseCaptured(platform: String, item: JSONObject, context: DanmakuConnectContext, elapsedMs: Long): DanmakuFrame? {
            val text = item.optString("text").takeIf(String::isNotBlank) ?: return null
            val name = item.optString("name").takeIf(String::isNotBlank)
            return ChatEventFactory.event(context, platform, text, item.optString("id"), item.optString("uid"), name,
                sourceTimeMs = item.optLong("time").takeIf { it > 0 }, elapsedMs = elapsedMs)
                .let { it.copy(event = it.event.copy(parserVersion = "$platform-web-observer-1")) }
        }

        internal fun captureScript(platform: String, bridge: String): String {
            require(platform in setOf("yy", "rednote") && bridge.matches(Regex("[A-Za-z0-9]+")))
            return """
                (function(){
                  if(window.__casttraceChatCapture)return;window.__casttraceChatCapture=true;
                  function send(x){try{window.$bridge.post(JSON.stringify(x));}catch(e){}}
                  ${if (platform == "yy") """
                  var seen=new WeakSet();
                  function scan(){
                    var list=document.querySelector('#chatroom-list');if(!list)return;
                    if(!list.__casttraceReady){list.__casttraceReady=true;list.querySelectorAll('li').forEach(function(n){seen.add(n);});send({state:'observing'});return;}
                    list.querySelectorAll('li.danmuItem.danmu').forEach(function(n){
                      if(seen.has(n))return;seen.add(n);
                      var nick=n.querySelector('.nickname'),words=n.querySelectorAll('.sayword');
                      if(!nick||!words.length)return;
                      var copy=nick.cloneNode(true);copy.querySelectorAll('i').forEach(function(x){x.remove();});
                      var text=Array.from(words).map(function(x){return x.textContent||'';}).join('');
                      if(text.trim())send({name:(copy.textContent||'').replace(/\s*[:：]\s*$/,'').trim(),text:text});
                    });
                  }
                  setInterval(scan,500);
                  """ else """
                  var NativeWS=window.WebSocket;
                  function receive(raw){
                    if(typeof raw!=='string'||raw.length>1048576)return;
                    try{
                      var outer=JSON.parse(raw),items=outer&&outer.b&&outer.b.d&&outer.b.d.b;
                      if(!Array.isArray(items))return;
                      items.forEach(function(item){try{
                        if(typeof item.d!=='string'||item.d.length>262144)return;
                        var bytes=Uint8Array.from(atob(item.d),function(c){return c.charCodeAt(0);});
                        var value=JSON.parse(new TextDecoder('utf-8').decode(bytes));
                        var data=typeof value.customData==='string'?JSON.parse(value.customData):value.customData;
                        if(!data||data.type!=='text'||typeof data.desc!=='string')return;
                        var user=data.profile||{};
                        send({id:String(data.commentId||value.msgId||''),uid:String(user.user_id||''),name:user.nickname||'',text:data.desc});
                      }catch(e){}});
                    }catch(e){}
                  }
                  function ChatWS(url,protocols){var ws=protocols===undefined?new NativeWS(url):new NativeWS(url,protocols);
                    ws.addEventListener('message',function(e){receive(e.data);});
                    ws.addEventListener('open',function(){send({state:'observing'});});return ws;}
                  ChatWS.prototype=NativeWS.prototype;Object.setPrototypeOf(ChatWS,NativeWS);window.WebSocket=ChatWS;
                  """}
                })();
            """.trimIndent()
        }
    }
}
