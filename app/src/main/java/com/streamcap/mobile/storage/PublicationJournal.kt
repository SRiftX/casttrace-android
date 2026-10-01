package com.streamcap.mobile.storage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable intent precedes external creation; unique names cover insert-before-URI crashes. */
internal class PublicationJournal(context: Context, key: String) {
    private val file = AtomicFile(File(File(context.filesDir, "publication-journal").apply { mkdirs() },
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"))

    fun read(): JSONObject? = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
        file.openRead().bufferedReader().use { JSONObject(it.readText()) } else null

    fun write(value: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    fun delete() = file.delete()
}
