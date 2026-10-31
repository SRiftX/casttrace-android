package com.streamcap.mobile.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Owns provider metadata queries and rename operations for published recordings. */
class RecordingMediaStoreOperations(
    private val context: Context,
    private val exportOperations: RecordingExportOperations,
) {
    fun publishedDurationSeconds(uriStrings: List<String>): Long = publishedPartDurationsSeconds(uriStrings).sum()

    fun publishedPartDurationsSeconds(uriStrings: List<String>): List<Long> = uriStrings.map { raw ->
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@map 0L
        val indexed = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Video.VideoColumns.DURATION), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        val milliseconds = if (indexed > 0L) indexed else runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        milliseconds.coerceAtLeast(0L) / 1_000L
    }

    fun renamePublished(uri: Uri, baseName: String): Uri {
        val base = baseName.trim()
        require(base.isNotEmpty() && base != "." && base != ".." && base.length <= 100 &&
            base.none { it.code < 32 || it in "\\/:*?\"<>|" }) { "文件名不能为空、过长或包含路径及特殊字符" }
        val oldName = exportOperations.displayName(uri)
        val extension = oldName.substringAfterLast('.', "")
        val newName = if (extension.isEmpty()) base else "$base.$extension"
        if (oldName == newName) return uri
        return when {
            uri.scheme == "file" -> {
                val source = File(requireNotNull(uri.path))
                val target = File(source.parentFile, newName)
                check(!target.exists()) { "同名文件已存在" }
                check(source.renameTo(target)) { "重命名失败，请检查目录权限" }
                Uri.fromFile(target)
            }
            DocumentsContract.isDocumentUri(context, uri) ->
                requireNotNull(DocumentsContract.renameDocument(context.contentResolver, uri, newName)) { "文档提供器拒绝重命名" }
            uri.authority == MediaStore.AUTHORITY -> {
                check(context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }, null, null) == 1) { "重命名失败，请检查文件权限" }
                uri
            }
            else -> error("此文件提供器不支持重命名")
        }
    }
}
