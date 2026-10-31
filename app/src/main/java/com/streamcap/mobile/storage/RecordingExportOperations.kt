package com.streamcap.mobile.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Export, share, and document-name operations kept separate from recording publication. */
class RecordingExportOperations(private val context: Context) {
    fun exportDirectory(uri: Uri): DocumentFile =
        requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "导出目录不可用" }

    fun shareIntent(source: Uri): Intent {
        val name = displayName(source)
        val sharedUri = asShareUri(source)
        return Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(sharedUri) ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, sharedUri)
            putExtra(Intent.EXTRA_TITLE, name)
            clipData = ClipData.newUri(context.contentResolver, name, sharedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(sources: List<Uri>): Intent {
        require(sources.isNotEmpty()) { "没有可分享的文件" }
        if (sources.size == 1) return shareIntent(sources.single())
        val uris = sources.map(::asShareUri)
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(context.contentResolver, "录制文件", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun displayName(uri: Uri): String = when (uri.scheme) {
        "file" -> File(requireNotNull(uri.path)).name
        "content" -> context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: error("文件不存在或无读取权限")
        else -> error("不支持此文件位置")
    }

    suspend fun exportCopy(source: Uri, destination: Uri, onProgress: (Long) -> Unit = {}): Long =
        withContext(Dispatchers.IO) {
            require(source != destination) { "导出位置不能是原文件" }
            try {
                var copied = 0L
                val input = requireNotNull(context.contentResolver.openInputStream(source)) { "原文件不可读取" }
                input.use {
                    val output = if (destination.scheme == "file") File(requireNotNull(destination.path)).outputStream()
                    else requireNotNull(context.contentResolver.openOutputStream(destination, "w")) { "导出位置不可写入" }
                    output.use {
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            onProgress(copied)
                        }
                    }
                }
                check(copied > 0) { "原文件为空" }
                copied
            } catch (failure: Throwable) {
                // Export destinations are newly created by the picker; retain the source on failure.
                runCatching { deleteDestination(destination) }
                throw failure
            }
        }

    /** Verify a previous successful export before skipping it on retry. Never overwrite it. */
    suspend fun sameContents(source: Uri, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openInputStream(source)).buffered().use { left ->
            requireNotNull(context.contentResolver.openInputStream(destination)).buffered().use { right ->
                val a = ByteArray(64 * 1024)
                val b = ByteArray(a.size)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = left.read(a)
                    if (count < 0) return@withContext right.read() == -1
                    var offset = 0
                    while (offset < count) {
                        val read = right.read(b, offset, count - offset)
                        if (read < 0) return@withContext false
                        offset += read
                    }
                    for (i in 0 until count) if (a[i] != b[i]) return@withContext false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        }
    }

    private fun asShareUri(source: Uri): Uri = if (source.scheme == "file") {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(source.path)))
    } else source

    private fun deleteDestination(destination: Uri) {
        if (destination.scheme == "content") {
            context.contentResolver.delete(destination, null, null)
        } else {
            File(destination.path ?: destination.toString()).delete()
        }
    }
}
