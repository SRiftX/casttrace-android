package com.streamcap.mobile.danmaku

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.FileOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

data class DanmakuStoreStats(val written: Long, val dropped: Long, val firstDroppedAt: Long?, val lastDroppedAt: Long?)

class DanmakuEventStore(private val file: File, capacity: Int = 512) : AutoCloseable {
    private val queue = ArrayBlockingQueue<DanmakuEvent>(capacity.coerceAtLeast(8))
    private val running = AtomicBoolean(true)
    @Volatile private var written = 0L
    @Volatile private var dropped = 0L
    @Volatile private var firstDroppedAt: Long? = null
    @Volatile private var lastDroppedAt: Long? = null
    @Volatile private var failure: Throwable? = null
    private val writer = Thread {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { stream ->
                BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8)).use { out ->
                    var lastFlush = System.nanoTime()
                    while (running.get() || queue.isNotEmpty()) {
                        val event = queue.poll(200, TimeUnit.MILLISECONDS)
                        if (event != null) {
                            out.append(event.json().toString()).append('\n')
                            written++
                        }
                        if (System.nanoTime() - lastFlush >= 1_000_000_000L) {
                            out.flush()
                            stream.fd.sync()
                            lastFlush = System.nanoTime()
                        }
                    }
                    out.flush()
                    stream.fd.sync()
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
        }.toString())
    }

    companion object {
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

private fun DanmakuEvent.json() = JSONObject().apply {
    put("schemaVersion", 1); put("sessionId", sessionId); put("platform", platform); put("eventId", eventId)
    put("receivedElapsedMs", receivedElapsedMs); put("receivedWallMs", receivedWallMs); put("clockEpochId", clockEpochId)
    put("sourceTimeMs", sourceTimeMs); put("mediaTimeMs", mediaTimeMs); put("timeQuality", timeQuality)
    put("userId", userId); put("userName", userName); put("text", text); put("rawType", rawType); put("parserVersion", parserVersion)
}

private fun fromJson(v: JSONObject) = DanmakuEvent(
    sessionId = v.getLong("sessionId"), platform = v.getString("platform"), eventId = v.optString("eventId").takeIf(String::isNotBlank),
    receivedElapsedMs = v.getLong("receivedElapsedMs"), receivedWallMs = v.getLong("receivedWallMs"), clockEpochId = v.getString("clockEpochId"),
    sourceTimeMs = v.optLong("sourceTimeMs").takeIf { !v.isNull("sourceTimeMs") }, mediaTimeMs = v.optLong("mediaTimeMs").takeIf { !v.isNull("mediaTimeMs") },
    timeQuality = v.optString("timeQuality").takeIf(String::isNotBlank), userId = v.optString("userId").takeIf(String::isNotBlank),
    userName = v.optString("userName").takeIf(String::isNotBlank), text = v.getString("text"), rawType = v.optString("rawType", "chat"),
    parserVersion = v.optString("parserVersion", "unknown"),
)
