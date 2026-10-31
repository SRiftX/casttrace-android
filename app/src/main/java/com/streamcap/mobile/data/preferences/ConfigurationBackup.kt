package com.streamcap.mobile.data.preferences

import androidx.room.withTransaction
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.data.database.StreamCapDatabase
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class BackupPreview(val rooms: List<LiveRoomEntity>, val format: String, val quality: String, val interval: Int)

object ConfigurationBackup {
    suspend fun export(preferences: AppPreferences, repository: StreamCapRepository): String {
        val settings = preferences.settings.first()
        val rooms = repository.rooms.first()
        return JSONObject().put("version", 1).put("format", settings.defaultFormat).put("quality", settings.defaultQuality)
            .put("interval", settings.checkIntervalSeconds).put("rooms", JSONArray().apply {
                rooms.forEach { room ->
                    // Share public room addresses only; query signatures and embedded credentials never enter a backup.
                    val uri = URI(room.normalizedUrl)
                    val publicUrl = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
                    put(JSONObject().put("url", publicUrl).put("platform", room.platform).put("name", room.anchorName)
                        .put("format", room.outputFormat).put("quality", room.quality).put("segmentEnabled", room.segmentEnabled)
                        .put("segmentSeconds", room.segmentSeconds).put("flv", room.flvDirectDownload)
                        .put("notifications", room.notificationsEnabled).put("interval", room.checkIntervalSeconds))
                }
            }).toString(2)
    }

    fun parse(text: String): BackupPreview {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "备份超过 1 MiB 限制" }
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "不支持此备份版本" }
        fun format(value: String) = value.also { require(it in setOf("MP4", "FLV", "TS")) { "保存格式无效" } }
        fun quality(value: String) = value.also { require(it in setOf("原画", "1080p", "720p", "480p", "流畅", "音频优先")) { "画质无效" } }
        fun interval(value: Int) = value.also { require(it in 15..3600) { "检查间隔无效" } }
        val list = root.getJSONArray("rooms")
        require(list.length() <= 500) { "一次最多导入 500 个直播间" }
        val rooms = (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            val url = row.getString("url")
            val parsed = URI(url)
            require(parsed.scheme in setOf("https", "http") && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.query == null && parsed.fragment == null) { "直播间地址无效或包含凭据，请重新添加" }
            val name = row.getString("name")
            require(name.isNotBlank() && name.length <= 200 && url.length <= 4096) { "直播间名称或地址过长" }
            val segment = row.getInt("segmentSeconds")
            require(segment in 60..604800) { "分段时长无效" }
            LiveRoomEntity(originalUrl = url, normalizedUrl = url, platform = row.getString("platform"), anchorName = name,
                roomTitle = null, enabled = true, autoRecord = false, scheduledMonitoringEnabled = false, taskStatus = TaskStatus.PAUSED,
                outputFormat = format(row.getString("format")), quality = quality(row.getString("quality")),
                segmentEnabled = row.getBoolean("segmentEnabled"), segmentSeconds = segment,
                flvDirectDownload = row.getBoolean("flv"), notificationsEnabled = row.getBoolean("notifications"),
                checkIntervalSeconds = interval(row.getInt("interval")))
        }
        return BackupPreview(rooms, format(root.getString("format")), quality(root.getString("quality")), interval(root.getInt("interval")))
    }

    suspend fun restore(
        preferences: AppPreferences,
        repository: StreamCapRepository,
        resolverRegistry: ResolverRegistry,
        database: StreamCapDatabase,
        preview: BackupPreview,
    ): Pair<Int, Int> {
        check(repository.activeSessions().isEmpty()) { "请先暂停并保存所有录制，再导入配置" }
        preview.rooms.forEach { resolverRegistry.resolverForPlatform(it.platform) }
        var added = 0
        var skipped = 0
        database.withTransaction {
            preview.rooms.forEach { room ->
                val dao = database.liveRoomDao()
                if (dao.getByNormalizedUrl(room.normalizedUrl) != null) skipped++
                else { dao.insert(room); added++ }
            }
        }
        preferences.restoreDefaults(preview.format, preview.quality, preview.interval)
        return added to skipped
    }
}
