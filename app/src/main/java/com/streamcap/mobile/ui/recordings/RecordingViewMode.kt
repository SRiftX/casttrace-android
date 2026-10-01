package com.streamcap.mobile.ui.recordings

enum class RecordingViewMode(val storedValue: String, val label: String) {
    COMPACT("compact", "摘要"),
    DETAILED("detailed", "详情");

    companion object {
        fun fromStored(value: String?): RecordingViewMode =
            entries.firstOrNull { it.storedValue == value } ?: COMPACT
    }
}
