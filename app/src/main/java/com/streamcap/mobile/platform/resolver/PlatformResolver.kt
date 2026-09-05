package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.platform.AndroidSupportStatus
import com.streamcap.mobile.platform.PlatformCatalog

interface PlatformResolver {
    val platformId: String

    fun supports(url: String): Boolean

    suspend fun resolveRoom(url: String): LiveRoomInfo

    suspend fun checkLive(
        room: LiveRoom,
        credential: PlatformCredential? = null,
    ): LiveStatus

    suspend fun getStreams(
        room: LiveRoom,
        credential: PlatformCredential? = null,
    ): List<StreamSource>
}

class UnsupportedPlatformException(url: String) : IllegalArgumentException(messageFor(url)) {
    companion object {
        private fun messageFor(url: String): String {
            val entry = PlatformCatalog.entries.firstOrNull { platform ->
                platform.domains.any { domain -> url.contains(domain, ignoreCase = true) }
            } ?: return "无法识别这个直播地址，请检查 URL 是否完整"
            return when (entry.status) {
                AndroidSupportStatus.FIRST_BATCH ->
                    "${entry.name}解析器未包含在当前版本中"
                AndroidSupportStatus.PLANNED ->
                    "已识别为${entry.name}，该平台正在等待 Android 适配"
                AndroidSupportStatus.NEEDS_REVIEW ->
                    "已识别为${entry.name}，平台状态需要重新验证"
                AndroidSupportStatus.AVAILABLE ->
                    "已识别地址，但没有找到可用解析器"
            }
        }
    }
}
