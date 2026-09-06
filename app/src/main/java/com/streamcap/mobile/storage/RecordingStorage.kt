package com.streamcap.mobile.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.content.ContentUris
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class RecordingStorage(private val context: Context) {
    fun shareIntent(source: Uri): android.content.Intent {
        val name = displayName(source)
        val sharedUri = if (source.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(source.path)))
        } else source
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(sharedUri) ?: "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, sharedUri)
            putExtra(android.content.Intent.EXTRA_TITLE, name)
            clipData = android.content.ClipData.newUri(context.contentResolver, name, sharedUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun displayName(uri: Uri): String = when (uri.scheme) {
        "file" -> File(requireNotNull(uri.path)).name
        "content" -> context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: error("文件不存在或无读取权限")
        else -> error("不支持此文件位置")
    }

    fun renamePublished(uri: Uri, baseName: String): Uri {
        val base = baseName.trim()
        require(base.isNotEmpty() && base != "." && base != ".." && base.length <= 100 &&
            base.none { it.code < 32 || it in "\\/:*?\"<>|" }) { "文件名不能为空、过长或包含路径及特殊字符" }
        val oldName = displayName(uri)
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

    suspend fun exportCopy(source: Uri, destination: Uri, onProgress: (Long) -> Unit = {}): Long =
        withContext(Dispatchers.IO) {
            require(source != destination) { "导出位置不能是原文件" }
            try {
                var copied = 0L
                val input = requireNotNull(context.contentResolver.openInputStream(source)) { "原文件不可读取" }
                input.use {
                    val output = requireNotNull(context.contentResolver.openOutputStream(destination, "w")) { "导出位置不可写入" }
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
                // The picker creates a new destination; never delete or rewrite the source.
                runCatching { deletePublished(destination.toString()) }
                throw failure
            }
        }

    fun recordingRoot(): File = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    data class DeleteResult(val deleted: Int, val failed: List<String>) {
        val successful: Boolean get() = failed.isEmpty()
    }

    fun deletePublished(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return false
        return if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            runCatching { File(uri.path ?: uriString).delete() }.getOrDefault(false)
        }
    }

    /** 删除显式记录的全部 URI；旧记录额外查找同名的 _001/_002 分段。 */
    fun deleteRecordingFiles(uriStrings: List<String>): DeleteResult {
        val targets = uriStrings.flatMap { uriString ->
            val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull()
            if (uri == null) emptyList() else listOf(uri) + mediaStoreSiblingParts(uri)
        }.distinctBy(android.net.Uri::toString)
        var deleted = 0
        val failed = mutableListOf<String>()
        targets.forEach { uri ->
            val result = runCatching {
                if (uri.scheme == "content") {
                    context.contentResolver.delete(uri, null, null) > 0 || contentUriIsMissing(uri)
                } else {
                    val file = File(uri.path ?: uri.toString())
                    file.delete() || !file.exists()
                }
            }.getOrDefault(false)
            if (result) deleted++ else failed += uri.toString()
        }
        return DeleteResult(deleted, failed)
    }

    private fun contentUriIsMissing(uri: android.net.Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> !cursor.moveToFirst() }
            ?: false

    private fun mediaStoreSiblingParts(current: android.net.Uri): List<android.net.Uri> {
        if (current.authority != MediaStore.AUTHORITY) return emptyList()
        val currentName = context.contentResolver.query(
            current,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: return emptyList()
        val match = PART_NAME.matchEntire(currentName) ?: return emptyList()
        val prefix = match.groupValues[1]
        val extension = match.groupValues[3]
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.Video.Media.getContentUri(MediaStore.getVolumeName(current)) }
                .getOrDefault(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return buildList {
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
                    val sibling = PART_NAME.matchEntire(cursor.getString(nameColumn)) ?: continue
                    if (sibling.groupValues[1] == prefix && sibling.groupValues[3].equals(extension, true)) {
                        add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                    }
                }
            }
        }
    }

    fun createTemporaryFile(roomName: String, extension: String): File {
        val baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
        val directory = File(baseDirectory, "pending-recordings").apply { mkdirs() }
        val safeName = roomName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(48)
            .ifBlank { "recording" }
        return File(directory, "${safeName}_${System.currentTimeMillis()}.$extension.part")
    }

    fun publish(
        temporary: File,
        displayName: String,
        mimeType: String,
        preferredDirectoryUri: String? = null,
    ): String {
        val finalName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        preferredDirectoryUri?.let { treeUri ->
            val directory = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri))
                ?: error("保存目录不可用，请重新选择目录")
            check(directory.canWrite()) { "保存目录没有写入权限，请重新选择目录" }
            val target = directory.createFile(mimeType, uniqueDocumentName(directory, finalName))
                ?: error("无法在选择的目录中创建文件")
            try {
                context.contentResolver.openOutputStream(target.uri, "w").use { output ->
                    requireNotNull(output) { "无法打开选择的保存目录" }
                    FileInputStream(temporary).use { input -> input.copyTo(output) }
                }
                temporary.delete()
                return target.uri.toString()
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StreamCap")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values),
            ) { "无法创建媒体文件" }
            try {
                context.contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "无法打开媒体文件" }
                    FileInputStream(temporary).use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                temporary.delete()
                return uri.toString()
            } catch (error: Throwable) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val targetDirectory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamCap",
        ).apply { mkdirs() }
        val target = uniqueFile(targetDirectory, finalName)
        temporary.copyTo(target)
        temporary.delete()
        return target.toURI().toString()
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val requested = File(directory, displayName)
        if (!requested.exists()) return requested
        val base = requested.nameWithoutExtension
        val extension = requested.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        return generateSequence(1) { it + 1 }
            .map { File(directory, "${base}_$it$extension") }
            .first { !it.exists() }
    }

    private fun uniqueDocumentName(directory: DocumentFile, displayName: String): String {
        if (directory.findFile(displayName) == null) return displayName
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        return generateSequence(1) { it + 1 }
            .map { "${base}_$it$extension" }
            .first { directory.findFile(it) == null }
    }

    private companion object {
        val PART_NAME = Regex("^(.*)_(\\d{3})(\\.[^.]+)$")
    }
}
