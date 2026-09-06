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
import kotlin.coroutines.coroutineContext

internal class RecordingPlaybackSourceResolver(private val context: Context) : AutoCloseable {
    private val snapshots = java.util.Collections.synchronizedList(mutableListOf<File>())
    private var closed = false
    override fun close() { synchronized(snapshots) { closed = true; snapshots.forEach(File::delete); snapshots.clear() } }

    suspend fun resolve(outputUri: String?, outputUris: List<String>, temporaryPath: String?, containerFormat: String): List<Uri> {
        val explicit = outputUris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter(::hasReadableMedia)
        if (explicit.isNotEmpty()) {
            return preparePublished(explicit, containerFormat)
        }
        if (outputUri.isNullOrBlank() || !hasReadableMedia(Uri.parse(outputUri))) {
            return temporaryPath?.let { temporaryParts(File(it)) }
                ?.map { Uri.fromFile(it) }
                .orEmpty()
        }
        val current = Uri.parse(outputUri)
        if (!hasReadableMedia(current)) return emptyList()
        val parts = mediaStoreParts(current)
        return preparePublished(parts.ifEmpty { listOf(current) }, containerFormat)
    }

    private suspend fun preparePublished(uris: List<Uri>, format: String): List<Uri> = when (format.lowercase()) {
        // Media3 can seek TS directly and playing each segment avoids copying a multi-GB recording into cache.
        "ts" -> uris
        "flv" -> uris.filter(::hasPlayableFlv).map { snapshot(it) }
        else -> uris
    }

    private fun temporaryParts(first: File): List<File> {
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
        }.sortedBy { it.first }.map { it.second }
    }

    private suspend fun snapshot(source: File): Uri = snapshot(Uri.fromFile(source), source.length())

    private suspend fun snapshot(source: Uri, frozenLength: Long? = null): Uri {
        val copy = File.createTempFile("recording-preview-", ".part", context.cacheDir)
        val target = File.createTempFile("recording-preview-", ".mp4", context.cacheDir)
        try {
            // Freeze the length before copying a file that the recorder is still appending.
            val length = frozenLength ?: context.contentResolver.openAssetFileDescriptor(source, "r")?.use {
                it.length.takeIf { size -> size >= 0 }
            }
            if (length != null) check(length <= (context.cacheDir.usableSpace - 32L * 1024 * 1024) / 2) {
                "空间不足，无法为此录制建立可快进的播放副本"
            }
            requireNotNull(context.contentResolver.openInputStream(source)) { "无法读取录制文件" }.use { input -> copy.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length ?: Long.MAX_VALUE
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    check(context.cacheDir.usableSpace > 32L * 1024 * 1024) { "空间不足，无法准备播放" }
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            } }
            check(copy.length() <= context.cacheDir.usableSpace - 32L * 1024 * 1024) { "空间不足，无法建立播放索引" }
            FfmpegMp4Muxer.convert(copy, target)
            coroutineContext.ensureActive()
            synchronized(snapshots) {
                check(!closed) { "播放页面已关闭" }
                snapshots.add(target)
            }
            return Uri.fromFile(target)
        } catch (failure: Exception) {
            target.delete()
            throw failure
        } finally { copy.delete() }
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
        val PART_NAME = Regex("^(.*)_(\\d{3})(\\.[^.]+)$")
    }
}
