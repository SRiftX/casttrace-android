package com.streamcap.mobile.platform.resolver
import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
class BigoResolver(private val client: OkHttpClient): PlatformResolver {
 override val supportsCredentials = true
 override val platformId="bigo"; override fun supports(url:String)=url.hasHost("bigo.tv")
 override suspend fun resolveRoom(url:String):LiveRoomInfo = resolveRoom(url, null)
 override suspend fun resolveRoom(url:String,credential:PlatformCredential?):LiveRoomInfo{val d=load(url,credential);return LiveRoomInfo(url,"Bigo",d.anchor,null)}
 override suspend fun checkLive(room:LiveRoom,credential:PlatformCredential?)=if(load(room.normalizedUrl,credential).sources.isEmpty())LiveStatus.UNKNOWN else LiveStatus.LIVE
 override suspend fun getStreams(room:LiveRoom,credential:PlatformCredential?)=load(room.normalizedUrl,credential).sources
 private suspend fun load(url:String,credential:PlatformCredential?=null):D{val id=url.substringBefore('?').trimEnd('/').substringAfterLast('/');require(id.isNotBlank());val headers=buildMap{put("User-Agent",DESKTOP_USER_AGENT);put("Origin","https://www.bigo.tv");put("Referer","https://www.bigo.tv/");credential?.cookie?.takeIf(String::isNotBlank)?.let{put("Cookie",it)}};val root=JSONObject(client.postForm("https://ta.bigo.tv/official_website/studio/getInternalStudioInfo",mapOf("siteId" to id),headers));val d=root.optJSONObject("data")?:error("Bigo room data missing");check(!d.optBoolean("needLogin")){"Bigo 此房间需要登录 Cookie，不能据此判定主播下播"};val a=d.optString("nick_name","Bigo");val s=if(d.optInt("alive")==1)listOfNotNull(d.optString("hls_src").takeIf{it.startsWith("https://") }?.let{StreamSource(it,StreamType.HLS,"原画",requiresFfmpeg=true,headers=headers)})else emptyList();check(d.optInt("alive")!=1||s.isNotEmpty()){"Bigo 正在直播，但没有返回可录制的 HLS 线路"};return D(a,s)}
 private data class D(val anchor:String,val sources:List<StreamSource>)
}
