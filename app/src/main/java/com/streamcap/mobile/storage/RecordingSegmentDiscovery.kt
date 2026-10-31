package com.streamcap.mobile.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns

/** Finds the published files that belong to a segmented recording. */
class RecordingSegmentDiscovery(
    private val context: Context,
    private val exportOperations: RecordingExportOperations,
) {
    /** Return all indexed MediaStore parts belonging to the same recording name. */
    fun publishedSiblingParts(uriStrings: List<String>): List<String> {
        val explicit = uriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        if (explicit.isEmpty()) return emptyList()
        val discovered = explicit.flatMap { uri ->
            listOf(uri) + runCatching { mediaStoreSiblingParts(uri) }.getOrDefault(emptyList())
        }.distinctBy(Uri::toString)
        return discovered.sortedWith(compareBy(
            { runCatching { siblingIndex(exportOperations.displayName(it)) }.getOrNull() ?: 1 },
            { runCatching { exportOperations.displayName(it) }.getOrDefault("") },
        )).map(Uri::toString)
    }

    /** Recover indexed files in a user selected folder when older records lost their URI list. */
    fun publishedSiblingParts(uriStrings: List<String>, roomName: String, startedAt: Long?): List<String> {
        val base = publishedSiblingParts(uriStrings).toMutableList()
        val seed = uriStrings.firstOrNull()?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return base
        if (!DocumentsContract.isDocumentUri(context, seed)) return base
        val safeRoom = RecordingNameFormatter.safeComponent(roomName)
        val parentId = runCatching {
            DocumentsContract.getDocumentId(seed).substringBeforeLast('/').ifBlank { return@runCatching null }
        }.getOrNull() ?: return base
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(seed, parentId)
        val seedName = runCatching { exportOperations.displayName(seed) }.getOrNull() ?: return base
        val extension = seedName.substringAfterLast('.', "")
        val seedPrefix = seedName.replace(
            Regex("_\\d{8}_\\d{6}_\\d{3}(?:_[0-9a-fA-F-]{36})?\\.${Regex.escape(extension)}$"),
            "",
        )
        val candidates = mutableListOf<Pair<Int, String>>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modified = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val display = cursor.getString(name)
                val index = Regex("_(\\d{3})(?:_[0-9a-fA-F-]{36})?\\.${Regex.escape(extension)}$", RegexOption.IGNORE_CASE)
                    .find(display)?.groupValues?.get(1)?.toIntOrNull()
                val lastModified = if (modified >= 0) cursor.getLong(modified) else 0L
                val candidatePrefix = display.replace(
                    Regex("_\\d{8}_\\d{6}_\\d{3}(?:_[0-9a-fA-F-]{36})?\\.${Regex.escape(extension)}$"),
                    "",
                )
                if (display.startsWith("${safeRoom}_") && candidatePrefix == seedPrefix && index != null &&
                    (startedAt == null || lastModified == 0L || lastModified >= startedAt - 60_000L)
                ) {
                    candidates += index to DocumentsContract.buildDocumentUriUsingTree(seed, cursor.getString(id)).toString()
                }
            }
        }
        val known = base.mapNotNull { uri ->
            runCatching { siblingIndex(exportOperations.displayName(Uri.parse(uri))) }.getOrNull()
        }.toMutableSet()
        candidates.sortedBy { it.first }.forEach { (index, uri) ->
            if (index !in known && (index - 1) in known) {
                base += uri
                known += index
            }
        }
        return base.distinct().sortedBy { uri ->
            runCatching { exportOperations.displayName(Uri.parse(uri)) }.getOrNull()?.let(::siblingIndex) ?: 1
        }
    }

    internal fun mediaStoreSiblingParts(current: Uri): List<Uri> {
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
        val currentDirectory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.contentResolver.query(
            current,
            arrayOf(MediaStore.Video.Media.RELATIVE_PATH),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } else null
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.Video.Media.getContentUri(MediaStore.getVolumeName(current)) }
                .getOrDefault(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return buildList {
            context.contentResolver.query(
                collection,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH)
                else arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("$prefix%$extension"),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val directoryColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH) else -1
                while (cursor.moveToNext()) {
                    val sibling = PART_NAME.matchEntire(cursor.getString(nameColumn)) ?: continue
                    if (sibling.groupValues[1] == prefix && sibling.groupValues[3].equals(extension, true) &&
                        (directoryColumn < 0 || cursor.getString(directoryColumn) == currentDirectory)
                    ) {
                        add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                    }
                }
            }
        }
    }

    private fun siblingIndex(name: String): Int? = PART_NAME.matchEntire(name)?.groupValues?.get(2)?.toIntOrNull()

    private companion object {
        val PART_NAME = Regex("^(.*)_(\\d{3})(?:_[0-9a-fA-F-]{36})?(\\.[^.]+)$")
    }
}
