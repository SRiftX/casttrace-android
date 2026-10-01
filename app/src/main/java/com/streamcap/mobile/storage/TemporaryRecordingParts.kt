package com.streamcap.mobile.storage

import java.io.File

object TemporaryRecordingParts {
    fun index(first: File, file: File): Int? {
        if (first.name == file.name) return 1
        val marker = listOf(".ts.part", ".flv.part").firstOrNull { first.name.endsWith(it) }
        val prefix = marker?.let { first.name.removeSuffix(it) } ?: first.nameWithoutExtension
        val suffix = marker ?: ".${first.extension}"
        return listOf(
            Regex("^${Regex.escape(prefix)}_(\\d{3})${Regex.escape(suffix)}$"),
            Regex("^${Regex.escape(first.nameWithoutExtension)}_(\\d{3})\\.${Regex.escape(first.extension)}$"),
        ).firstNotNullOfOrNull { it.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
