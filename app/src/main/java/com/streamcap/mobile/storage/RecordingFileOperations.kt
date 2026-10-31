package com.streamcap.mobile.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.UUID

/** File-system paths and temporary recording allocation. */
class RecordingFileOperations(private val context: Context) {
    fun recordingRoot(): File = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir

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
}
