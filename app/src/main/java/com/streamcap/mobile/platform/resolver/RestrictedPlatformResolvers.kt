package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A dedicated boundary for protocols whose playback authorization is not yet implemented. */
abstract class RestrictedPlatformResolver : PlatformResolver {
    protected abstract val hosts: Array<String>
    protected abstract val requirement: String
    override fun supports(url: String) = url.hasHost(*hosts)
    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        require(supports(url) && url.toHttpUrlOrNull()?.scheme == "https") { "直播地址无效" }
        return LiveRoomInfo(url, platformId, platformId, null)
    }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?): LiveStatus =
        throw IllegalStateException(requirement)
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?): List<StreamSource> =
        throw IllegalStateException(requirement)
}

class LiveMeResolver : RestrictedPlatformResolver() {
    override val platformId = "liveme"
    override val hosts = arrayOf("liveme.com")
    override val requirement = "LiveMe 播放请求需要平台动态签名，当前不能安全取得媒体线路"
}

class HaixiuResolver : RestrictedPlatformResolver() {
    override val platformId = "haixiu"
    override val hosts = arrayOf("haixiutv.com")
    override val requirement = "嗨秀播放接口需要动态 JS 签名，当前不能安全取得媒体线路"
}

class LehaiResolver : RestrictedPlatformResolver() {
    override val platformId = "lehai"
    override val hosts = arrayOf("lehaitv.com")
    override val requirement = "乐嗨播放接口需要动态 JS 签名，当前不能安全取得媒体线路"
}

class MiguResolver : RestrictedPlatformResolver() {
    override val platformId = "migu"
    override val hosts = arrayOf("miguvideo.com")
    override val requirement = "咪咕播放 URL 需要平台 WASM 签名，当前不能安全取得媒体线路"
}

class PopkonResolver : RestrictedPlatformResolver() {
    override val platformId = "popkontv"
    override val hosts = arrayOf("popkontv.com")
    override val requirement = "PopkonTV 播放接口需要有效账号和年龄验证，当前不能取得媒体线路"
}
