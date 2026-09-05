package com.streamcap.mobile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LiveRoomEntity::class,
        RecordingSessionEntity::class,
        TaskLogEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StreamCapDatabase : RoomDatabase() {
    abstract fun liveRoomDao(): LiveRoomDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun taskLogDao(): TaskLogDao
}
