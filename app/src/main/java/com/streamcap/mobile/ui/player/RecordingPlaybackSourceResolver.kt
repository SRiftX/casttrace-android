package com.streamcap.mobile.ui.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.streamcap.mobile.recording.flv.FlvFileInspector
import java.io.File
import com.streamcap.mobile.recording.ffmpeg.FfmpegMp4Muxer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

internal class RecordingPlaybackSourceResolver(private val context: Context) : AutoCloseable {
    private val snapshots = java.util.Collections.synchronizedList(mutableListOf<File>())
    init { com.streamcap.mobile.storage.PreviewCache.acquire() }
    private var closed = false
    override fun close() { synchronized(snapshots) { if (!closed) com.streamcap.mobile.storage.PreviewCache.release(); closed = true; snapshots.forEach(File::delete); snapshots.clear() } }

    suspend fun resolve(outputUri: String?, outputUris: List<String>, temporaryPath: String?, containerFormat: String,
        includeActiveTail: Boolean = false, refreshPublished: (suspend () -> List<String>)? = null): List<Uri> {
        repeat(3) { attempt ->
            val latest = refreshPublished?.invoke() ?: outputUris
            val ownedBefore = synchronized(snapshots) { snapshots.toSet() }
            try {
                val result = resolveOnce(latest.firstOrNull() ?: outputUri, latest, temporaryPath, containerFormat, includeActiveTail)
                if (refreshPublished == null || refreshPublished() == latest) return result
            } catch (race: java.io.FileNotFoundException) {
                if (refreshPublished == null) throw race
            }
            synchronized(snapshots) {
                snapshots.filterNot { it in ownedBefore }.forEach { it.delete() }
                snapshots.retainAll(ownedBefore)
            }
            if (attempt < 2) kotlinx.coroutines.delay(150)
        }
        error("分段正在保存，播放列表已变化，请稍后刷新已录内容")
    }

    private suspend fun resolveOnce(outputUri: String?, outputUris: List<String>, temporaryPath: String?, containerFormat: String,
        includeActiveTail: Boolean): List<Uri> {
        val explicit = outputUris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter(::hasReadableMedia)
        if (outputUris.isNotEmpty()) {
            val published = preparePublished(explicit, containerFormat)
            if (!includeActiveTail) return published
            // Count recorded URIs, including unreadable ones, so removing a saved
            // file cannot shift indices and append an already published segment.
            val remaining = temporaryPath?.let { temporaryParts(File(it)) }.orEmpty()
                .filter { it.first > outputUris.size }
            if (remaining.isNotEmpty() && remaining.first().first != outputUris.size + 1) {
                throw java.io.FileNotFoundException("分段正在发布")
            }
            return published + remaining.map { snapshot(it.second) }
        }
        if (outputUri.isNullOrBlank() || !hasReadableMedia(Uri.parse(outputUri))) {
            return temporaryPath?.let { temporaryParts(File(it)) }
                ?.map { snapshot(it.second) }
                .orEmpty()
        }
        val current = Uri.parse(outputUri)
        if (!hasReadableMedia(current)) return emptyList()
        val parts = mediaStoreParts(current)
        return preparePublished(parts.ifEmpty { listOf(current) }, containerFormat)
    }

    private suspend fun preparePublished(uris: List<Uri>, format: String): List<Uri> = when (format.lowercase()) {
        // Media3 reports some saved transport streams as duration 0 and unseekable.
        // Build an indexed, disposable MP4 preview without changing the recording.
        "ts" -> uris.map { snapshot(it, isFlv = false) }
        "flv" -> uris.filter(::hasPlayableFlv).map { snapshot(it) }
        else -> uris
    }

    private fun temporaryParts(first: File): List<Pair<Int, File>> {
        val marker = listOf(".ts.part", ".flv.part").firstOrNull { first.name.endsWith(it) }
        val prefix = marker?.let { first.name.removeSuffix(it) } ?: first.nameWithoutExtension
        val suffix = marker ?: ".${first.extension}"
        val patterns = listOf(
            Regex("^${Regex.escape(prefix)}_(\\d{3})${Regex.escape(suffix)}$"),
            // Files made by older builds used `name.ts_002.part`.
            Regex("^${Regex.escape(first.nameWithoutExtension)}_(\\d{3})\\.${Regex.escape(first.extension)}$"),
        ).distinctBy(Regex::pattern)
        return first.parentFile?.listFiles().orEmpty().mapNotNull { file ->
            val index = if (file.name == first.name) 1 else patterns.firstNotNullOfOrNull {
                it.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }
            index?.takeIf { file.length() > 1024 }?.let { it to file }
        }.sortedBy { it.first }
    }

    private suspend fun snapshot(source: File): Uri = snapshot(Uri.fromFile(source), source.length(),
        isFlv = source.name.contains(".flv", true))

    private suspend fun snapshot(source: Uri, frozenLength: Long? = null, isFlv: Boolean = true): Uri {
        val target = File.createTempFile("recording-preview-", ".mp4", context.cacheDir)
        try {
            // Freeze the length before copying a file that the recorder is still appending.
            val length = frozenLength ?: context.contentResolver.openAssetFileDescriptor(source, "r")?.use {
                it.length.takeIf { size -> size >= 0 }
            }
            require(length == null || length > 0) { "录制文件为空，暂不能建立播放副本" }
            if (length != null) check(length <= (context.cacheDir.usableSpace - PREVIEW_RESERVE_BYTES) / 11 * 10) {
                "空间不足，无法为此录制建立可快进的播放副本"
            }
            requireNotNull(context.contentResolver.openInputStream(source)) { "无法读取录制文件" }.use { input ->
                coroutineScope {
                    val pipe = android.os.ParcelFileDescriptor.createPipe()
                    val writer = async(Dispatchers.IO) {
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            val jobContext = coroutineContext
                            // Active local sources always supply a frozen length. Some
                            // providers cannot report saved-file size; stream those to EOF.
                            com.streamcap.mobile.storage.FrozenMediaCopy.copy(input, output, length ?: Long.MAX_VALUE, isFlv) {
                                jobContext.ensureActive()
                                check(context.cacheDir.usableSpace > PREVIEW_RESERVE_BYTES) { "空间不足，无法准备播放" }
                            }
                        }
                    }
                    try {
                        FfmpegMp4Muxer.convertPath("/proc/self/fd/${pipe[0].fd}", target)
                        writer.await()
                    } finally {
                        pipe[0].close()
                        writer.cancel()
                    }
                }
            }
            coroutineContext.ensureActive()
            synchronized(snapshots) {
                check(!closed) { "播放页面已关闭" }
                snapshots.add(target)
            }
            return Uri.fromFile(target)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    private fun hasPlayableFlv(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.buffered()?.use(FlvFileInspector::hasMediaTags) == true
    }.getOrDefault(false)

    private fun hasReadableMedia(uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            "file" -> File(uri.path.orEmpty()).length() > 0L
            "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length != 0L
            } == true
            else -> false
        }
    }.getOrDefault(false)

    private fun mediaStoreParts(current: Uri): List<Uri> {
        if (current.authority != MediaStore.AUTHORITY) return listOf(current)
        val currentName = queryCurrentName(current) ?: return listOf(current)
        val currentMatch = PART_NAME.matchEntire(currentName) ?: return listOf(current)
        val prefix = currentMatch.groupValues[1]
        val extension = currentMatch.groupValues[3]
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                MediaStore.Video.Media.getContentUri(MediaStore.getVolumeName(current))
            }.getOrDefault(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val candidates = mutableListOf<Pair<Int, Uri>>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%$extension"),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val candidateName = cursor.getString(nameColumn)
                val match = PART_NAME.matchEntire(candidateName) ?: continue
                val index = match.groupValues[2].toIntOrNull() ?: continue
                if (match.groupValues[1] == prefix && match.groupValues[3].equals(extension, true)) {
                    candidates += index to ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                }
            }
        }
        return candidates.sortedBy { it.first }.map { it.second }.ifEmpty { listOf(current) }
    }

    private fun queryCurrentName(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) null
        else cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
    }

    private companion object {
        // Leave more than the recorder's 128 MiB low-space threshold.
        const val PREVIEW_RESERVE_BYTES = 256L * 1024 * 1024
        val PART_NAME = Regex("^(.*)_(\\d{3})(\\.[^.]+)$")
    }
}
