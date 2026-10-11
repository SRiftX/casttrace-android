package com.streamcap.mobile.danmaku

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

data class DanmakuStoreStats(val written: Long, val dropped: Long, val firstDroppedAt: Long?, val lastDroppedAt: Long?)

data class DanmakuFilter(
    val query: String = "",
    val user: String = "",
    val platform: String = "",
    val eventType: DanmakuEventType? = null,
) {
    private val needle = query.trim()
    private val userNeedle = user.trim()
    private val platformNeedle = platform.trim()
    fun matches(event: DanmakuEvent): Boolean =
        (platformNeedle.isEmpty() || event.platform.equals(platformNeedle, ignoreCase = true)) &&
            (eventType == null || event.eventType == eventType) &&
            (userNeedle.isEmpty() || event.user?.name?.contains(userNeedle, ignoreCase = true) == true ||
                event.user?.id?.contains(userNeedle, ignoreCase = true) == true ||
                event.userName?.contains(userNeedle, ignoreCase = true) == true ||
                event.userId?.contains(userNeedle, ignoreCase = true) == true) &&
            (needle.isEmpty() || event.text.contains(needle, ignoreCase = true))
}

data class DanmakuEventPage(val events: List<DanmakuEvent>, val nextOffset: Long?, val scannedBytes: Long, val totalBytes: Long)
data class DanmakuCoverage(val dropped: Long?, val eventTypes: Map<DanmakuEventType, Long>)

class DanmakuEventStore(private val file: File, capacity: Int = 512) : AutoCloseable {
    private val queue = ArrayBlockingQueue<DanmakuEvent>(capacity.coerceAtLeast(8))
    private val running = AtomicBoolean(true)
    private val previousCoverage = if (file.isFile) runCatching {
        JSONObject(File(file.parentFile, "coverage.json").readText())
    }.getOrNull() else null
    @Volatile private var written = previousCoverage?.optLong("eventCount") ?: 0L
    @Volatile private var dropped = previousCoverage?.optLong("droppedCount") ?: 0L
    @Volatile private var firstDroppedAt: Long? = previousCoverage?.optLong("firstDroppedAt")
        ?.takeIf { previousCoverage?.isNull("firstDroppedAt") == false }
    @Volatile private var lastDroppedAt: Long? = previousCoverage?.optLong("lastDroppedAt")
        ?.takeIf { previousCoverage?.isNull("lastDroppedAt") == false }
    @Volatile private var failure: Throwable? = null
    private val dirty = AtomicBoolean(false)
    private val eventTypeCounts = LongArray(DanmakuEventType.entries.size) { index ->
        previousCoverage?.optJSONObject("eventTypes")?.optLong(DanmakuEventType.entries[index].name) ?: 0L
    }
    private val writer = Thread {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { stream ->
                BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8)).use { out ->
                    var lastFlush = System.nanoTime()
                    var lastSync = lastFlush
                    while (running.get() || queue.isNotEmpty()) {
                        val event = queue.poll(200, TimeUnit.MILLISECONDS)
                        if (event != null) {
                            out.append(event.toJson().toString()).append('\n')
                            written++
                            eventTypeCounts[event.eventType.ordinal]++
                            dirty.set(true)
                        }
                        val now = System.nanoTime()
                        if (dirty.get() && now - lastFlush >= FLUSH_INTERVAL_NS) {
                            out.flush()
                            lastFlush = System.nanoTime()
                        }
                        if (dirty.get() && now - lastSync >= SYNC_INTERVAL_NS) {
                            out.flush(); stream.fd.sync(); dirty.set(false); lastSync = System.nanoTime()
                        }
                    }
                    if (dirty.get()) { out.flush(); stream.fd.sync(); dirty.set(false) }
                }
            }
            File(file.parentFile, "committed.marker").writeText(written.toString())
        } catch (error: Throwable) {
            failure = error
        }
    }.also { it.start() }

    fun append(event: DanmakuEvent): Boolean {
        failure?.let { throw IllegalStateException("弹幕文件保存失败", it) }
        if (!running.get() || failure != null) return false
        if (queue.offer(event)) return true
        dropped++
        val now = System.currentTimeMillis()
        if (firstDroppedAt == null) firstDroppedAt = now
        lastDroppedAt = now
        return false
    }

    fun stats() = DanmakuStoreStats(written, dropped, firstDroppedAt, lastDroppedAt)
    override fun close() {
        if (running.compareAndSet(true, false)) writer.join(5_000)
        check(!writer.isAlive) { "弹幕文件写入超时" }
        failure?.let { throw IllegalStateException("弹幕文件保存失败", it) }
        File(file.parentFile, "coverage.json").writeText(JSONObject().apply {
            put("eventCount", written); put("droppedCount", dropped)
            put("firstDroppedAt", firstDroppedAt); put("lastDroppedAt", lastDroppedAt)
            put("eventTypes", JSONObject().apply {
                DanmakuEventType.entries.forEach { type -> put(type.name, eventTypeCounts[type.ordinal]) }
            })
        }.toString())
    }

    companion object {
        private const val FLUSH_INTERVAL_NS = 1_000_000_000L
        private const val SYNC_INTERVAL_NS = 5_000_000_000L
        private const val INDEX_BLOCK_LINES = 256
        private data class IndexBlock(val start: Long, val end: Long, val minTime: Long, val maxTime: Long)
        private data class WindowIndex(val path: String, val length: Long, val modified: Long, val blocks: List<IndexBlock>)
        @Volatile private var cachedIndex: WindowIndex? = null

        private fun indexFile(file: File) = File(file.parentFile, "${file.name}.time-index")

        private fun loadIndex(file: File): WindowIndex? = runCatching {
            DataInputStream(indexFile(file).inputStream().buffered()).use { input ->
                if (input.readInt() != 1) return@use null
                val length = input.readLong()
                val modified = input.readLong()
                val count = input.readInt()
                if (length > file.length() || length < 0L || count < 0 || count > length / 2) return@use null
                val blocks = List(count) {
                    IndexBlock(input.readLong(), input.readLong(), input.readLong(), input.readLong())
                }
                if (blocks.any { it.start < 0L || it.end > length || it.end <= it.start || it.minTime > it.maxTime }) return@use null
                WindowIndex(file.absolutePath, length, modified, blocks)
            }
        }.getOrNull()

        private fun saveIndex(file: File, value: WindowIndex) {
            runCatching {
                val target = indexFile(file)
                val staging = File(target.parentFile, "${target.name}.tmp")
                DataOutputStream(FileOutputStream(staging).buffered()).use { output ->
                    output.writeInt(1)
                    output.writeLong(value.length)
                    output.writeLong(value.modified)
                    output.writeInt(value.blocks.size)
                    value.blocks.forEach { block ->
                        output.writeLong(block.start); output.writeLong(block.end)
                        output.writeLong(block.minTime); output.writeLong(block.maxTime)
                    }
                }
                if (!staging.renameTo(target)) {
                    target.delete()
                    staging.renameTo(target)
                }
            }
        }

        private fun index(file: File, isCancelled: () -> Boolean): WindowIndex = synchronized(this) {
            val path = file.absolutePath
            val length = file.length()
            val modified = file.lastModified()
            val previous = cachedIndex?.takeIf { it.path == path } ?: loadIndex(file)
            previous?.takeIf { it.length == length && it.modified == modified }?.let { return@synchronized it.also { cachedIndex = it } }
            val blocks = mutableListOf<IndexBlock>()
            RandomAccessFile(file, "r").use { raf ->
                val growing = previous?.takeIf { it.length < length }
                val resumeAt = growing?.blocks?.lastOrNull()?.start ?: 0L
                if (resumeAt > 0L) blocks += growing!!.blocks.dropLast(1)
                raf.seek(resumeAt)
                var start = resumeAt
                var lines = 0
                var minTime = Long.MAX_VALUE
                var maxTime = Long.MIN_VALUE
                fun finish() {
                    if (minTime <= maxTime) blocks += IndexBlock(start, raf.filePointer, minTime, maxTime)
                    start = raf.filePointer
                    lines = 0
                    minTime = Long.MAX_VALUE
                    maxTime = Long.MIN_VALUE
                }
                while (raf.filePointer < length) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val line = raf.readLine() ?: break
                    if (raf.filePointer > length) break
                    val time = runCatching {
                        JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))
                            .takeIf { !it.isNull("mediaTimeMs") }?.getLong("mediaTimeMs")
                    }.getOrNull()
                    if (time != null) {
                        minTime = minOf(minTime, time)
                        maxTime = maxOf(maxTime, time)
                    }
                    if (++lines == INDEX_BLOCK_LINES) finish()
                }
                if (lines > 0) finish()
            }
            WindowIndex(path, length, modified, blocks).also { cachedIndex = it; saveIndex(file, it) }
        }

        internal fun clearIndexCacheForTest() { cachedIndex = null }

        fun readWindow(file: File, fromMediaMs: Long, untilMediaMs: Long, maxEvents: Int = 12_000,
                       filter: DanmakuFilter = DanmakuFilter(), isCancelled: () -> Boolean = { false }): List<DanmakuEvent> =
            if (!file.isFile || maxEvents <= 0 || untilMediaMs <= fromMediaMs) emptyList() else buildList {
                val blocks = index(file, isCancelled).blocks
                RandomAccessFile(file, "r").use { raf ->
                    for (block in blocks) {
                        if (isCancelled()) throw java.util.concurrent.CancellationException()
                        if (size >= maxEvents) break
                        if (block.maxTime < fromMediaMs || block.minTime >= untilMediaMs) continue
                        raf.seek(block.start)
                        while (raf.filePointer < block.end && size < maxEvents) {
                            if (isCancelled()) throw java.util.concurrent.CancellationException()
                            val line = raf.readLine() ?: break
                            runCatching { fromJson(JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))) }
                                .getOrNull()?.let { event ->
                                    val time = event.mediaTimeMs
                                    if (time != null && time >= fromMediaMs && time < untilMediaMs && filter.matches(event)) add(event)
                                }
                        }
                    }
                }
            }

        fun readPage(file: File, sessionId: Long, offset: Long = 0L, limit: Int = 100,
                     filter: DanmakuFilter = DanmakuFilter(), isCancelled: () -> Boolean = { false }): DanmakuEventPage {
            if (!file.isFile) return DanmakuEventPage(emptyList(), null, 0L, 0L)
            require(limit in 1..500)
            return RandomAccessFile(file, "r").use { raf ->
                val end = raf.length()
                raf.seek(offset.coerceIn(0L, end))
                val events = ArrayList<DanmakuEvent>(limit)
                var partialTail = false
                while (raf.filePointer < end && events.size < limit) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val lineStart = raf.filePointer
                    val line = raf.readLine() ?: break
                    // An active writer may leave a partial last line. Retry it on the next page.
                    if (raf.filePointer == end && (end == 0L || run {
                            raf.seek(end - 1); raf.readByte().toInt() != 10
                        })) {
                        raf.seek(lineStart)
                        partialTail = true
                        break
                    }
                    val event = runCatching { fromJson(JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))) }.getOrNull()
                    if (event != null && event.sessionId == sessionId && filter.matches(event)) events += event
                }
                val cursor = raf.filePointer
                DanmakuEventPage(events, cursor.takeIf { it < end && !partialTail }, cursor, end)
            }
        }

        fun countMatching(file: File, sessionId: Long, filter: DanmakuFilter,
                          isCancelled: () -> Boolean = { false }): Long {
            if (!file.isFile) return 0L
            var count = 0L
            RandomAccessFile(file, "r").use { raf ->
                val end = raf.length()
                while (raf.filePointer < end) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val line = raf.readLine() ?: break
                    val event = runCatching { fromJson(JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))) }.getOrNull()
                    if (event?.sessionId == sessionId && filter.matches(event)) count++
                }
            }
            return count
        }

        fun recover(file: File): List<DanmakuEvent> = if (!file.isFile) emptyList() else buildList {
            RandomAccessFile(file, "rw").use { raf ->
                var lastGood = 0L
                while (true) {
                    val start = raf.filePointer
                    val line = raf.readLine() ?: break
                    if (raf.filePointer == raf.length() && raf.length() > 0) {
                        raf.seek(raf.length() - 1)
                        if (raf.readByte().toInt() != 10) { raf.setLength(start); break }
                    }
                    try {
                        val event = fromJson(JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)))
                        check(isEmpty() || first().sessionId == event.sessionId) { "跨录制任务弹幕" }
                        add(event); lastGood = raf.filePointer
                    }
                    catch (_: Exception) { raf.setLength(start); break }
                }
                if (raf.length() != lastGood) raf.setLength(lastGood)
            }
        }
    }
}

internal fun DanmakuEvent.toJson() = JSONObject().apply {
    put("schemaVersion", 1); put("sessionId", sessionId); put("platform", platform); put("eventId", eventId)
    put("receivedElapsedMs", receivedElapsedMs); put("receivedWallMs", receivedWallMs); put("clockEpochId", clockEpochId)
    put("sourceTimeMs", sourceTimeMs); put("mediaTimeMs", mediaTimeMs); put("timeQuality", timeQuality)
    put("userId", userId); put("userName", userName); put("text", text); put("rawType", rawType); put("parserVersion", parserVersion)
    put("eventType", eventType.name); put("amount", amount); put("currency", currency); put("colorRgb", colorRgb)
    put("user", user?.let { JSONObject().apply { put("id", it.id); put("name", it.name); put("avatarUrl", it.avatarUrl); put("level", it.level); put("badgeName", it.badgeName); put("badgeLevel", it.badgeLevel); put("guardLevel", it.guardLevel) } })
    put("attributes", JSONObject(attributes))
    put("segmentId", segmentId)
}

internal fun fromJson(v: JSONObject) = DanmakuEvent(
    sessionId = v.getLong("sessionId"), platform = v.getString("platform"), eventId = v.optString("eventId").takeIf(String::isNotBlank),
    receivedElapsedMs = v.getLong("receivedElapsedMs"), receivedWallMs = v.getLong("receivedWallMs"), clockEpochId = v.getString("clockEpochId"),
    sourceTimeMs = v.optLong("sourceTimeMs").takeIf { !v.isNull("sourceTimeMs") }, mediaTimeMs = v.optLong("mediaTimeMs").takeIf { !v.isNull("mediaTimeMs") },
    timeQuality = v.optString("timeQuality").takeIf(String::isNotBlank), userId = v.optString("userId").takeIf(String::isNotBlank),
    userName = v.optString("userName").takeIf(String::isNotBlank), text = v.getString("text"), rawType = v.optString("rawType", "chat"),
    parserVersion = v.optString("parserVersion", "unknown"),
    eventType = runCatching { DanmakuEventType.valueOf(v.optString("eventType")) }.getOrElse { DanmakuEventType.fromRawType(v.optString("rawType")) },
    user = v.optJSONObject("user")?.let { user -> DanmakuUser(
        user.optString("id").takeIf(String::isNotBlank), user.optString("name").takeIf(String::isNotBlank),
        user.optString("avatarUrl").takeIf(String::isNotBlank), user.optInt("level").takeIf { !user.isNull("level") },
        user.optString("badgeName").takeIf(String::isNotBlank), user.optInt("badgeLevel").takeIf { !user.isNull("badgeLevel") },
        user.optInt("guardLevel").takeIf { !user.isNull("guardLevel") },
    ) },
    amount = v.optLong("amount").takeIf { !v.isNull("amount") }, currency = v.optString("currency").takeIf(String::isNotBlank),
    colorRgb = v.optInt("colorRgb").takeIf { !v.isNull("colorRgb") },
    attributes = v.optJSONObject("attributes")?.let { json -> json.keys().asSequence().associateWith { key -> json.optString(key) } } ?: emptyMap(),
    segmentId = v.optString("segmentId").takeIf(String::isNotBlank),
)
