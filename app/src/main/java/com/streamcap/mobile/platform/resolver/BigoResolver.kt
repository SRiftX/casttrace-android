package com.streamcap.mobile.platform.resolver
import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
class BigoResolver(private val client: OkHttpClient): PlatformResolver {
 override val platformId="bigo"; override fun supports(url:String)=url.hasHost("bigo.tv")
 override suspend fun resolveRoom(url:String):LiveRoomInfo{val d=load(url);return LiveRoomInfo(url,"Bigo",d.anchor,null)}
 override suspend fun checkLive(room:LiveRoom,credential:PlatformCredential?)=if(load(room.normalizedUrl).sources.isEmpty())LiveStatus.UNKNOWN else LiveStatus.LIVE
 override suspend fun getStreams(room:LiveRoom,credential:PlatformCredential?)=load(room.normalizedUrl).sources
 private suspend fun load(url:String):D{val id=url.substringBefore('?').trimEnd('/').substringAfterLast('/');require(id.isNotBlank());val root=JSONObject(client.postForm("https://ta.bigo.tv/official_website/studio/getInternalStudioInfo",mapOf("siteId" to id)));val d=root.optJSONObject("data")?:error("Bigo room data missing");val a=d.optString("nick_name","Bigo");val s=if(d.optInt("alive")==1)listOfNotNull(d.optString("hls_src").takeIf{it.startsWith("https://") }?.let{StreamSource(it,StreamType.HLS,"原画",requiresFfmpeg=true)})else emptyList();return D(a,s)}
 private data class D(val anchor:String,val sources:List<StreamSource>)
}
