package com.streamcap.mobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN mediaType TEXT NOT NULL DEFAULT '视频'")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN outputFormat TEXT NOT NULL DEFAULT 'MP4'")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN flvDirectDownload INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN storageDirectoryUri TEXT")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN segmentEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN segmentSeconds INTEGER NOT NULL DEFAULT 1800")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN scheduledMonitoringEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE live_rooms ADD COLUMN notificationsEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recording_sessions ADD COLUMN outputUris TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE recording_sessions SET outputUris = outputUri WHERE outputUri IS NOT NULL")
    }
}
