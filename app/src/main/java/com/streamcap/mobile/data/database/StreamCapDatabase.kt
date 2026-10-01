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
    version = 6,
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
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE live_rooms ADD COLUMN oneTimeStartAt INTEGER")
                db.execSQL("ALTER TABLE live_rooms ADD COLUMN oneTimeEndAt INTEGER")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN danmakuPath TEXT")
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN danmakuEventCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recording_sessions ADD COLUMN danmakuDroppedCount INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
