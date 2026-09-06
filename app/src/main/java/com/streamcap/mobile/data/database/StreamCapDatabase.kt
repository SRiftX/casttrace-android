package com.streamcap.mobile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LiveRoomEntity::class,
        RecordingSessionEntity::class,
        TaskLogEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StreamCapDatabase : RoomDatabase() {
    abstract fun liveRoomDao(): LiveRoomDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun taskLogDao(): TaskLogDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) =
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN roomTitle TEXT")
        }
    }
}
