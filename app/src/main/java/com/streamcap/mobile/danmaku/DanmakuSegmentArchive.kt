package com.streamcap.mobile.danmaku

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** Seals a drained session file against published media durations. The original remains readable. */
object DanmakuSegmentArchive {
    fun seal(source: File, sessionId: Long, mediaUris: List<String>, durationsMs: List<Long>): File {
        require(source.isFile && mediaUris.isNotEmpty() && mediaUris.size == durationsMs.size)
        require(durationsMs.all { it > 0L }) { "分段时长不可用，不能推断弹幕归属" }
        val destination = File(source.parentFile, "segments")
        if (File(destination, "manifest.json").isFile) return destination
        val staging = File(source.parentFile, "segments.tmp")
        if (staging.exists()) staging.deleteRecursively()
        check(staging.mkdirs()) { "不能创建弹幕分段目录" }
        val ends = LongArray(durationsMs.size)
        durationsMs.forEachIndexed { index, duration -> ends[index] = Math.addExact(if (index == 0) 0L else ends[index - 1], duration) }
        val counts = LongArray(durationsMs.size + 1)
        val writers = object : LinkedHashMap<Int, java.io.BufferedWriter>(8, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, java.io.BufferedWriter>?): Boolean {
                if (size <= 4) return false
                eldest?.value?.close()
                return true
            }
        }
        fun name(index: Int) = if (index == durationsMs.size) "unmapped.jsonl" else "part-${(index + 1).toString().padStart(4, '0')}.jsonl"
        try {
            RandomAccessFile(source, "r").use { input ->
                val end = input.length()
                while (input.filePointer < end) {
                    val line = input.readLine() ?: break
                    val event = runCatching { fromJson(JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))) }.getOrNull()
                        ?.takeIf { it.sessionId == sessionId } ?: continue
                    val mediaTime = event.mediaTimeMs
                    var index = if (mediaTime == null || mediaTime < 0L || mediaTime >= ends.last()) durationsMs.size
                        else ends.binarySearch(mediaTime).let { result -> if (result >= 0) result + 1 else -result - 1 }
                    index = index.coerceAtMost(durationsMs.size)
                    val writer = writers[index] ?: java.io.FileOutputStream(File(staging, name(index)), true)
                        .buffered(8 * 1024).writer(StandardCharsets.UTF_8).buffered().also { writers[index] = it }
                    writer.append(event.copy(segmentId = if (index == durationsMs.size) null else "part-${(index + 1).toString().padStart(4, '0')}").toJson().toString()).append('\n')
                    counts[index]++
                }
            }
            writers.values.forEach { it.close() }
            writers.clear()
            val segments = JSONArray()
            durationsMs.indices.forEach { index ->
                File(staging, name(index)).createNewFile()
                segments.put(JSONObject().apply {
                    put("segmentId", "part-${(index + 1).toString().padStart(4, '0')}")
                    put("file", name(index)); put("mediaUri", mediaUris[index])
                    put("startPlayableMs", if (index == 0) 0L else ends[index - 1])
                    put("endPlayableMs", ends[index]); put("eventCount", counts[index])
                    put("timeQuality", "received-estimate"); put("closedCleanly", true)
                })
            }
            File(staging, "manifest.json").writeText(JSONObject().apply {
                put("schemaVersion", 1); put("sessionId", sessionId)
                put("segments", segments); put("unmappedCount", counts.last())
                put("source", source.name)
            }.toString())
            check(!destination.exists() && staging.renameTo(destination)) { "弹幕分段清单发布失败" }
            return destination
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}
