package com.streamcap.mobile.platform.resolver
import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
class PandaResolver(private val client:OkHttpClient):PlatformResolver{
 override val platformId="pandatv";override fun supports(url:String)=url.hasHost("pandalive.co.kr","panda.tv")
 override suspend fun resolveRoom(url:String):LiveRoomInfo{val d=load(url);return LiveRoomInfo(url,"PandaTV",d.anchor,null)}
 override suspend fun checkLive(room:LiveRoom,credential:PlatformCredential?)=if(load(room.normalizedUrl,credential).sources.isEmpty())LiveStatus.UNKNOWN else LiveStatus.LIVE
 override suspend fun getStreams(room:LiveRoom,credential:PlatformCredential?)=load(room.normalizedUrl,credential).sources
 private suspend fun load(url:String,credential:PlatformCredential?=null):D{val id=url.substringBefore('?').trimEnd('/').substringAfterLast('/');val h=buildMap{put("Origin","https://www.pandalive.co.kr");put("Referer","https://www.pandalive.co.kr/");put("User-Agent",DESKTOP_USER_AGENT);credential?.cookie?.let{put("Cookie",it)}};val info=JSONObject(client.postForm("https://api.pandalive.co.kr/v1/member/bj",mapOf("userId" to id,"info" to "media fanGrade"),h));val bj=info.optJSONObject("bjInfo")?:error("Panda room data missing");val a=bj.optString("nick",id);if(!info.has("media"))return D(a,emptyList());val play=JSONObject(client.postForm("https://api.pandalive.co.kr/v1/live/play",mapOf("action" to "watch","userId" to id,"password" to "","shareLinkType" to ""),h));val u=play.optJSONObject("PlayList")?.optJSONArray("hls")?.optJSONObject(0)?.optString("url");return D(a,listOfNotNull(u?.takeIf{it.startsWith("https://")}?.let{StreamSource(it,StreamType.HLS,"原画",headers=h,requiresFfmpeg=true)}))}
 private data class D(val anchor:String,val sources:List<StreamSource>)
}
