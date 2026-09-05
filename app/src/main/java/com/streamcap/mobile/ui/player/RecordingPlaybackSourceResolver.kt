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
    override fun close() { synchronized(snapshots) { snapshots.forEach(File::delete); snapshots.clear() } }

    suspend fun resolve(outputUri: String?, outputUris: List<String>, temporaryPath: String?, containerFormat: String): List<Uri> {
        val explicit = outputUris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            .filter(::hasReadableMedia)
        if (explicit.isNotEmpty()) {
            return if (containerFormat.equals("flv", ignoreCase = true)) explicit.filter(::hasPlayableFlv) else explicit
        }
        if (outputUri.isNullOrBlank() || !hasReadableMedia(Uri.parse(outputUri))) {
            return temporaryPath?.let { temporaryParts(File(it)) }?.map { snapshot(it) }.orEmpty()
        }
        val current = Uri.parse(outputUri)
        if (!hasReadableMedia(current)) return emptyList()
        val parts = mediaStoreParts(current)
        if (!containerFormat.equals("flv", ignoreCase = true)) return parts.ifEmpty { listOf(current) }
        return parts.filter(::hasPlayableFlv).ifEmpty { listOf(current) }
    }

    private fun temporaryParts(first: File): List<File> {
        val stem = first.nameWithoutExtension
        val pattern = Regex("^${Regex.escape(stem)}_(\\d{3})\\.${Regex.escape(first.extension)}$")
        return first.parentFile?.listFiles().orEmpty().mapNotNull { file ->
            val index = if (file.name == first.name) 1 else pattern.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            index?.takeIf { file.length() > 1024 }?.let { it to file }
        }.sortedBy { it.first }.map { it.second }
    }

    private suspend fun snapshot(source: File): Uri {
        val copy = File.createTempFile("recording-preview-", ".part", context.cacheDir)
        val target = File.createTempFile("recording-preview-", ".mp4", context.cacheDir)
        try {
            // Freeze the length before copying a file that the recorder is still appending.
            val length = source.length()
            source.inputStream().use { input -> copy.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            } }
            FfmpegMp4Muxer.convert(copy, target)
            coroutineContext.ensureActive()
            snapshots.add(target)
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
