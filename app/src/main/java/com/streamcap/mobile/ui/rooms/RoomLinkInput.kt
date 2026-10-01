package com.streamcap.mobile.ui.rooms

internal object RoomLinkInput {
    fun extract(text: String): String? = com.streamcap.mobile.platform.resolver.RoomUrlInput.extract(text)

    fun lines(text: String): List<String> = text.lineSequence().map(String::trim).filter(String::isNotBlank).take(200).toList()
    fun exceedsLimit(text: String): Boolean = text.lineSequence().count { it.isNotBlank() } > 200
}
