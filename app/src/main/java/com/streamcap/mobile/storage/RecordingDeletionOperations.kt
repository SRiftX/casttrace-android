package com.streamcap.mobile.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.io.File

/** Handles deletion and Android's user-authorization flow for published recordings. */
class RecordingDeletionOperations(
    private val context: Context,
    private val segmentDiscovery: RecordingSegmentDiscovery,
) {
    fun deletePublished(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            runCatching { File(uri.path ?: uriString).delete() }.getOrDefault(false)
        }
    }

    fun deleteRecordingFiles(
        uriStrings: List<String>,
        deletedBySystem: Set<String> = emptySet(),
    ): RecordingStorage.DeleteResult {
        val targets = uriStrings.flatMap { uriString ->
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            if (uri == null) emptyList() else listOf(uri) + runCatching {
                segmentDiscovery.mediaStoreSiblingParts(uri)
            }.getOrDefault(emptyList())
        }.distinctBy(Uri::toString)
        var deleted = 0
        val failed = mutableListOf<String>()
        val mediaAuthorizationUris = mutableListOf<Uri>()
        var authorization: RecordingStorage.DeleteAuthorization? = null
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
                    uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                        mediaAuthorizationUris += uri
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && restricted is android.app.RecoverableSecurityException ->
                        if (authorization == null) authorization = RecordingStorage.DeleteAuthorization(
                            sender = restricted.userAction.actionIntent.intentSender,
                        )
                    DocumentsContract.isTreeUri(uri) && authorization == null -> authorization =
                        RecordingStorage.DeleteAuthorization(
                            tree = DocumentsContract.buildTreeDocumentUri(
                                requireNotNull(uri.authority),
                                DocumentsContract.getTreeDocumentId(uri),
                            ),
                        )
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
                RecordingStorage.DeleteAuthorization(
                    sender = MediaStore.createDeleteRequest(context.contentResolver, mediaAuthorizationUris).intentSender,
                    deletedBySystem = mediaAuthorizationUris.map(Uri::toString),
                )
            }.getOrDefault(authorization)
        }
        return RecordingStorage.DeleteResult(deleted, failed, authorization)
    }

    private fun contentUriIsMissing(uri: Uri): Boolean {
        if (uri.authority == MediaStore.AUTHORITY) {
            val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        }
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> !cursor.moveToFirst() }
            ?: false
    }
}
