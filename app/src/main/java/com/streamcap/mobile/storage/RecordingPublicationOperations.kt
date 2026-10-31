package com.streamcap.mobile.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.content.ContentUris
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Publishes completed temporary segments and keeps their recovery journal idempotent. */
class RecordingPublicationOperations(
    private val context: Context,
    private val deletionOperations: RecordingDeletionOperations,
) {
    fun publish(
        temporary: File,
        displayName: String,
        mimeType: String,
        preferredDirectoryUri: String? = null,
        roomFolder: String? = null,
        retainTemporary: Boolean = false,
        publicationKey: String? = null,
        onProgress: (Long) -> Unit = {},
    ): String = synchronized(publishLock) {
        if (publicationKey == null) return@synchronized publishInternal(
            temporary, displayName, mimeType, preferredDirectoryUri, roomFolder, retainTemporary, onProgress = onProgress,
        )
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
            check(size != null && (size < 0 || size == entry.getLong("bytes"))) {
                "已保存分段不可读取或大小改变，保留临时文件"
            }
            makeVisible(uri)
            return@synchronized uri.toString()
        }
        // The caller acknowledges only after its database commit, so recovery input stays available.
        publishInternal(
            temporary,
            entry.getString("name"),
            entry.getString("mime"),
            directory,
            folder,
            retainTemporary = true,
            existing = existing?.let(Uri::parse),
            onAllocated = { uri ->
                entry.put("uri", uri.toString())
                journal.write(entry)
            },
            onCopied = { uri ->
                entry.put("uri", uri.toString()).put("bytes", temporary.length()).put("ready", true)
                journal.write(entry)
            },
            onProgress = onProgress,
            journaled = true,
        )
    }

    fun acknowledgePublication(key: String) = synchronized(publishLock) {
        PublicationJournal(context, key).delete()
    }

    /** Used only when the user deletes a stopped recording, before deleting its source. */
    fun discardPublication(key: String): Boolean = synchronized(publishLock) {
        val journal = PublicationJournal(context, key)
        val entry = journal.read() ?: return@synchronized true
        val uri = entry.optString("uri").ifBlank { null } ?: findPublication(
            entry.getString("name"),
            entry.getString("directory").ifBlank { null },
            entry.getString("folder").ifBlank { null },
        )?.toString()
        if (uri != null && !deletionOperations.deleteRecordingFiles(listOf(uri)).successful) return@synchronized false
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
            return context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use {
                if (it.moveToFirst()) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.getLong(0))
                } else null
            }
        }
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamCap${safeFolder?.let { "/$it" } ?: ""}/$name",
        )
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
            val directory = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
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
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/StreamCap${safeFolder?.let { "/$it" } ?: ""}",
                )
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
    }
}
