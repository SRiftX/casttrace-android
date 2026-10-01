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
import java.util.UUID

class RecordingStorage(private val context: Context) {
    fun exportDirectory(uri: Uri): DocumentFile =
        requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "导出目录不可用" }
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

    fun shareIntent(sources: List<Uri>): android.content.Intent {
        require(sources.isNotEmpty()) { "没有可分享的文件" }
        if (sources.size == 1) return shareIntent(sources.single())
        val uris = sources.map { source ->
            if (source.scheme == "file") androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.files", File(requireNotNull(source.path))) else source
        }
        return android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = android.content.ClipData.newUri(context.contentResolver, "录制文件", uris.first()).apply {
                uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
            }
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
                // The picker creates a new destination; never delete or rewrite the source.
                runCatching { deletePublished(destination.toString()) }
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

    fun recordingRoot(): File = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
    data class DeleteAuthorization(val sender: android.content.IntentSender? = null, val tree: Uri? = null,
        val deletedBySystem: List<String> = emptyList())

    data class DeleteResult(val deleted: Int, val failed: List<String>, val authorization: DeleteAuthorization? = null) {
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
    fun deleteRecordingFiles(uriStrings: List<String>, deletedBySystem: Set<String> = emptySet()): DeleteResult {
        val targets = uriStrings.flatMap { uriString ->
            val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull()
            if (uri == null) emptyList() else listOf(uri) + runCatching { mediaStoreSiblingParts(uri) }.getOrDefault(emptyList())
        }.distinctBy(android.net.Uri::toString)
        var deleted = 0
        val failed = mutableListOf<String>()
        val mediaAuthorizationUris = mutableListOf<Uri>()
        var authorization: DeleteAuthorization? = null
        targets.forEach { uri ->
            val result = try {
                if (uri.toString() in deletedBySystem) {
                    true
                } else if (uri.scheme == "content" && runCatching { contentUriIsMissing(uri) }.getOrDefault(false)) {
                    true
                } else if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri) || contentUriIsMissing(uri)
                } else if (uri.scheme == "content") {
                    context.contentResolver.delete(uri, null, null) > 0 || contentUriIsMissing(uri)
                } else {
                    val file = File(uri.path ?: uri.toString())
                    file.delete() || !file.exists()
                }
            } catch (restricted: SecurityException) {
                when {
                    uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> mediaAuthorizationUris += uri
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && restricted is android.app.RecoverableSecurityException ->
                        if (authorization == null) authorization = DeleteAuthorization(sender = restricted.userAction.actionIntent.intentSender)
                    DocumentsContract.isTreeUri(uri) && authorization == null -> authorization = DeleteAuthorization(
                        tree = DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri)))
                }
                false
            } catch (_: java.io.FileNotFoundException) {
                runCatching { contentUriIsMissing(uri) }.getOrDefault(false)
            } catch (_: Exception) {
                false
            }
            if (result) deleted++ else failed += uri.toString()
        }
        if (mediaAuthorizationUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            authorization = runCatching {
                DeleteAuthorization(sender = MediaStore.createDeleteRequest(context.contentResolver, mediaAuthorizationUris).intentSender,
                    deletedBySystem = mediaAuthorizationUris.map(Uri::toString))
            }.getOrDefault(authorization)
        }
        return DeleteResult(deleted, failed, authorization)
    }

    private fun contentUriIsMissing(uri: android.net.Uri): Boolean {
        // Scoped media queries can hide foreign files when read access was denied.
        if (uri.authority == MediaStore.AUTHORITY) {
            val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        }
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> !cursor.moveToFirst() }
            ?: false
    }

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
        return File(directory, "${safeName}_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension.part")
    }

    fun publish(
        temporary: File,
        displayName: String,
        mimeType: String,
        preferredDirectoryUri: String? = null,
        roomFolder: String? = null,
        retainTemporary: Boolean = false,
        publicationKey: String? = null,
        onProgress: (Long) -> Unit = {},
    ): String {
        return synchronized(publishLock) {
            if (publicationKey == null) return@synchronized publishInternal(
                temporary, displayName, mimeType, preferredDirectoryUri, roomFolder, retainTemporary, onProgress = onProgress)
            val journal = PublicationJournal(context, publicationKey)
            val entry = journal.read() ?: org.json.JSONObject().apply {
                val extension = displayName.substringAfterLast('.', "")
                val base = displayName.substringBeforeLast('.', displayName).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                put("name", "${base}_${UUID.randomUUID()}${if (extension.isEmpty()) "" else ".$extension"}")
                put("directory", preferredDirectoryUri ?: "")
                put("folder", roomFolder ?: "")
                put("mime", mimeType)
                put("ready", false)
            }.also(journal::write)
            val directory = entry.getString("directory").ifBlank { null }
            val folder = entry.getString("folder").ifBlank { null }
            val existing = entry.optString("uri").ifBlank { null }
                ?: findPublication(entry.getString("name"), directory, folder)?.toString()
            if (entry.optBoolean("ready")) {
                val uri = Uri.parse(requireNotNull(existing) { "已保存分段的位置缺失，保留临时文件" })
                val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                check(size != null && (size < 0 || size == entry.getLong("bytes"))) { "已保存分段不可读取或大小改变，保留临时文件" }
                makeVisible(uri)
                return@synchronized uri.toString()
            }
            // Never remove recovery input here. The caller acknowledges only after its DB commit.
            val result = publishInternal(temporary, entry.getString("name"), entry.getString("mime"),
                directory, folder, true, existing?.let(Uri::parse), onAllocated = { uri ->
                    entry.put("uri", uri.toString()); journal.write(entry)
                }, onCopied = { uri ->
                    entry.put("uri", uri.toString()).put("bytes", temporary.length()).put("ready", true)
                    journal.write(entry)
                }, onProgress = onProgress, journaled = true)
            result
        }
    }

    fun acknowledgePublication(key: String) = synchronized(publishLock) {
        PublicationJournal(context, key).delete()
    }

    /** Used only when the user deletes a stopped recording, before deleting its source. */
    fun discardPublication(key: String): Boolean = synchronized(publishLock) {
        val journal = PublicationJournal(context, key)
        val entry = journal.read() ?: return@synchronized true
        val uri = entry.optString("uri").ifBlank { null } ?: findPublication(entry.getString("name"),
            entry.getString("directory").ifBlank { null }, entry.getString("folder").ifBlank { null })?.toString()
        if (uri != null && !deleteRecordingFiles(listOf(uri)).successful) return@synchronized false
        journal.delete()
        true
    }

    private fun makeVisible(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            check(context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null) == 1) { "媒体发布失败，保留临时文件" }
        }
    }

    private fun findPublication(name: String, directory: String?, folder: String?): Uri? {
        val safeFolder = folder?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()?.take(80)?.ifBlank { null }
        if (directory != null) {
            val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(directory)))
            check(root.canWrite()) { "保存目录权限已失效，保留临时文件，请恢复目录权限后重试" }
            val target = if (safeFolder == null) root else root.findFile(safeFolder)
            return target?.findFile(name)?.uri
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val collection = MediaStore.setIncludePending(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            return context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name), null)?.use {
                if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0)) else null
            }
        }
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamCap${safeFolder?.let { "/$it" } ?: ""}/$name")
        return file.takeIf { it.exists() }?.let(Uri::fromFile)
    }

    private fun publishInternal(
        temporary: File,
        displayName: String,
        mimeType: String,
        preferredDirectoryUri: String? = null,
        roomFolder: String? = null,
        retainTemporary: Boolean = false,
        existing: Uri? = null,
        onAllocated: (Uri) -> Unit = {},
        onCopied: (Uri) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        journaled: Boolean = false,
    ): String {
        val finalName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val safeFolder = roomFolder?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()?.take(80)?.ifBlank { null }
        preferredDirectoryUri?.let { treeUri ->
            val directory = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri))
                ?: error("保存目录不可用，请重新选择目录")
            check(directory.canWrite()) { "保存目录没有写入权限，请重新选择目录" }
            val targetDirectory = safeFolder?.let { folder ->
                directory.findFile(folder)?.takeIf { it.isDirectory } ?: directory.createDirectory(folder)
            } ?: directory
            requireNotNull(targetDirectory) { "无法创建直播间文件夹" }
            val targetUri = existing ?: (targetDirectory.createFile(mimeType, uniqueDocumentName(targetDirectory, finalName))
                ?: error("无法在选择的目录中创建文件")).uri
            onAllocated(targetUri)
            try {
                context.contentResolver.openOutputStream(targetUri, "wt").use { output ->
                    requireNotNull(output) { "无法打开选择的保存目录" }
                    copyPublication(temporary, output, onProgress)
                }
                onCopied(targetUri)
                if (!retainTemporary) temporary.delete()
                return targetUri.toString()
            } catch (error: Throwable) {
                // Journaled partial output is reused on retry; never discard its recovery source.
                if (!journaled) runCatching { context.contentResolver.delete(targetUri, null, null) }
                throw error
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StreamCap${safeFolder?.let { "/$it" } ?: ""}")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = existing ?: requireNotNull(
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values),
            ) { "无法创建媒体文件" }
            onAllocated(uri)
            try {
                context.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "无法打开媒体文件" }
                    copyPublication(temporary, output, onProgress)
                }
                onCopied(uri)
                makeVisible(uri)
                if (!retainTemporary) temporary.delete()
                return uri.toString()
            } catch (error: Throwable) {
                if (!journaled) runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        }

        val targetDirectory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamCap${safeFolder?.let { "/$it" } ?: ""}",
        ).apply { mkdirs() }
        val target = existing?.path?.let(::File) ?: uniqueFile(targetDirectory, finalName)
        onAllocated(Uri.fromFile(target))
        try {
            target.outputStream().use { copyPublication(temporary, it, onProgress) }
        } catch (failure: Throwable) {
            if (!journaled) target.delete()
            throw failure
        }
        onCopied(Uri.fromFile(target))
        if (!retainTemporary) temporary.delete()
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

    private fun copyPublication(source: File, output: java.io.OutputStream, onProgress: (Long) -> Unit) {
        FileInputStream(source).use { input ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
                onProgress(total)
            }
            check(total > 0L) { "待保存分段为空，保留恢复源" }
        }
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
        val publishLock = Any()
        val PART_NAME = Regex("^(.*)_(\\d{3})(\\.[^.]+)$")
    }
}
