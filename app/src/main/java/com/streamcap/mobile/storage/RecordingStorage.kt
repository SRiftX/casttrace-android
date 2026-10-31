package com.streamcap.mobile.storage

import android.content.Context
import java.io.File
import android.net.Uri

class RecordingStorage(
    context: Context,
    private val fileOperations: RecordingFileOperations,
    private val exportOperations: RecordingExportOperations,
    private val segmentDiscovery: RecordingSegmentDiscovery = RecordingSegmentDiscovery(context, exportOperations),
    private val deletionOperations: RecordingDeletionOperations = RecordingDeletionOperations(context, segmentDiscovery),
    private val publicationOperations: RecordingPublicationOperations = RecordingPublicationOperations(context, deletionOperations),
    private val mediaStoreOperations: RecordingMediaStoreOperations = RecordingMediaStoreOperations(context, exportOperations),
) {
    constructor(context: Context) : this(
        context,
        RecordingFileOperations(context),
        RecordingExportOperations(context),
    )

    fun publishedDurationSeconds(uriStrings: List<String>): Long = mediaStoreOperations.publishedDurationSeconds(uriStrings)

    fun publishedPartDurationsSeconds(uriStrings: List<String>): List<Long> = mediaStoreOperations.publishedPartDurationsSeconds(uriStrings)

    fun exportDirectory(uri: Uri) = exportOperations.exportDirectory(uri)
    fun shareIntent(source: Uri) = exportOperations.shareIntent(source)
    fun shareIntent(sources: List<Uri>) = exportOperations.shareIntent(sources)
    fun displayName(uri: Uri): String = exportOperations.displayName(uri)

    fun renamePublished(uri: Uri, baseName: String): Uri = mediaStoreOperations.renamePublished(uri, baseName)

    suspend fun exportCopy(source: Uri, destination: Uri, onProgress: (Long) -> Unit = {}): Long =
        exportOperations.exportCopy(source, destination, onProgress)

    suspend fun sameContents(source: Uri, destination: Uri): Boolean = exportOperations.sameContents(source, destination)

    fun recordingRoot(): File = fileOperations.recordingRoot()
    data class DeleteAuthorization(val sender: android.content.IntentSender? = null, val tree: Uri? = null,
        val deletedBySystem: List<String> = emptyList())

    data class DeleteResult(val deleted: Int, val failed: List<String>, val authorization: DeleteAuthorization? = null) {
        val successful: Boolean get() = failed.isEmpty()
    }

    fun deletePublished(uriString: String?): Boolean = deletionOperations.deletePublished(uriString)

    fun deleteRecordingFiles(uriStrings: List<String>, deletedBySystem: Set<String> = emptySet()): DeleteResult =
        deletionOperations.deleteRecordingFiles(uriStrings, deletedBySystem)

    fun publishedSiblingParts(uriStrings: List<String>): List<String> =
        segmentDiscovery.publishedSiblingParts(uriStrings)

    fun publishedSiblingParts(uriStrings: List<String>, roomName: String, startedAt: Long?): List<String> =
        segmentDiscovery.publishedSiblingParts(uriStrings, roomName, startedAt)

    fun createTemporaryFile(roomName: String, extension: String): File {
        return fileOperations.createTemporaryFile(roomName, extension)
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
    ): String = publicationOperations.publish(
        temporary,
        displayName,
        mimeType,
        preferredDirectoryUri,
        roomFolder,
        retainTemporary,
        publicationKey,
        onProgress,
    )

    fun acknowledgePublication(key: String) = publicationOperations.acknowledgePublication(key)

    /** Used only when the user deletes a stopped recording, before deleting its source. */
    fun discardPublication(key: String): Boolean = publicationOperations.discardPublication(key)
}
