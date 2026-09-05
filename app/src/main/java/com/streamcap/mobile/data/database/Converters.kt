package com.streamcap.mobile.data.database

import androidx.room.TypeConverter
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus

class Converters {
    @TypeConverter
    fun liveStatusToString(value: LiveStatus): String = value.name

    @TypeConverter
    fun stringToLiveStatus(value: String): LiveStatus = LiveStatus.valueOf(value)

    @TypeConverter
    fun taskStatusToString(value: TaskStatus): String = value.name

    @TypeConverter
    fun stringToTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun streamTypeToString(value: StreamType): String = value.name

    @TypeConverter
    fun stringToStreamType(value: String): StreamType = StreamType.valueOf(value)

    @TypeConverter
    fun stringListToString(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun stringToStringList(value: String): List<String> =
        value.lineSequence().filter(String::isNotBlank).toList()
}
