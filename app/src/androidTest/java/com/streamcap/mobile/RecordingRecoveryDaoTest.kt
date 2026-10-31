package com.streamcap.mobile

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.data.database.*
import com.streamcap.mobile.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingRecoveryDaoTest {
    @Test fun changingRecordingFormatAfterPauseCannotRestoreAutomaticRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.liveRoomDao()
            val id = dao.insert(LiveRoomEntity(originalUrl = "format", normalizedUrl = "format", platform = "qa", anchorName = "format", roomTitle = null, scheduledMonitoringEnabled = true, autoRecord = true))
            dao.setStatus(id, LiveStatus.LIVE, TaskStatus.PAUSED, 10)
            dao.updateRecordingOptions(id, "视频", "TS", "原画", false, null, true, 1800, true, 60, 11)
            val room = dao.getById(id)!!
            assertEquals(TaskStatus.PAUSED, room.taskStatus)
            assertFalse(room.scheduledMonitoringEnabled)
            assertFalse(room.autoRecord)
        } finally { db.close() }
    }
    @Test fun everyPausedRoomStatusDisablesAutomaticRecordingAtomically() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.liveRoomDao()
            val id = dao.insert(LiveRoomEntity(
                originalUrl = "pause", normalizedUrl = "pause", platform = "qa", anchorName = "pause",
                roomTitle = null, scheduledMonitoringEnabled = true, autoRecord = true,
                oneTimeStartAt = 100, oneTimeEndAt = 200,
            ))
            dao.setStatus(id, LiveStatus.LIVE, TaskStatus.PAUSED, 10)
            val paused = dao.getById(id)!!
            assertEquals(TaskStatus.PAUSED, paused.taskStatus)
            assertFalse(paused.scheduledMonitoringEnabled)
            assertFalse(paused.autoRecord)
            assertNull(paused.oneTimeStartAt)
            assertNull(paused.oneTimeEndAt)

            dao.setScheduledMonitoringEnabled(id, true, 11)
            val resumed = dao.getById(id)!!
            assertEquals(TaskStatus.WAITING, resumed.taskStatus)
            assertTrue(resumed.scheduledMonitoringEnabled)
        } finally { db.close() }
    }

    @Test fun oneTimeWindowPersistsAndExpiresWithoutTouchingOtherRooms() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.liveRoomDao()
            val id = dao.insert(LiveRoomEntity(originalUrl = "window", normalizedUrl = "window", platform = "qa", anchorName = "window", roomTitle = null))
            val other = dao.insert(LiveRoomEntity(originalUrl = "other", normalizedUrl = "other", platform = "qa", anchorName = "other", roomTitle = null, scheduledMonitoringEnabled = true))
            dao.setOneTimeWindow(id, 100, 200, 50)
            assertEquals(100L, dao.getById(id)?.oneTimeStartAt)
            assertTrue(dao.getById(id)?.scheduledMonitoringEnabled == true)
            dao.expireOneTimeWindow(id, 199)
            assertEquals(200L, dao.getById(id)?.oneTimeEndAt)
            dao.expireOneTimeWindow(id, 200)
            assertNull(dao.getById(id)?.oneTimeEndAt)
            assertFalse(dao.getById(id)?.scheduledMonitoringEnabled == true)
            assertTrue(dao.getById(other)?.scheduledMonitoringEnabled == true)
        } finally { db.close() }
    }
    @Test fun repairClearsAutomaticRecordingOnPreviouslyPausedRooms() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.liveRoomDao()
            val eligibleId = dao.insert(LiveRoomEntity(
                originalUrl = "eligible", normalizedUrl = "eligible", platform = "qa", anchorName = "eligible",
                roomTitle = null, scheduledMonitoringEnabled = true, autoRecord = true, taskStatus = TaskStatus.PAUSED,
            ))
            val manualOnlyId = dao.insert(LiveRoomEntity(
                originalUrl = "manual", normalizedUrl = "manual", platform = "qa", anchorName = "manual",
                roomTitle = null, scheduledMonitoringEnabled = false, taskStatus = TaskStatus.PAUSED,
            ))
            val disabledId = dao.insert(LiveRoomEntity(
                originalUrl = "disabled", normalizedUrl = "disabled", platform = "qa", anchorName = "disabled",
                roomTitle = null, enabled = false, scheduledMonitoringEnabled = true, taskStatus = TaskStatus.PAUSED,
            ))

            assertEquals(2, dao.repairPausedRoomSettings(10))

            assertEquals(TaskStatus.PAUSED, dao.getById(eligibleId)!!.taskStatus)
            assertFalse(dao.getById(eligibleId)!!.scheduledMonitoringEnabled)
            assertFalse(dao.getById(eligibleId)!!.autoRecord)
            assertEquals(TaskStatus.PAUSED, dao.getById(manualOnlyId)!!.taskStatus)
            assertEquals(TaskStatus.PAUSED, dao.getById(disabledId)!!.taskStatus)
            assertFalse(dao.getById(disabledId)!!.scheduledMonitoringEnabled)
            assertTrue(db.recordingSessionDao().getActive().isEmpty())
        } finally { db.close() }
    }

    @Test fun recoveryClearsStaleRecordingButPreservesNewTaskAndUserPause() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val rooms = db.liveRoomDao()
            val sessions = db.recordingSessionDao()
            val id = rooms.insert(LiveRoomEntity(originalUrl = "qa", normalizedUrl = "qa", platform = "qa", anchorName = "qa", roomTitle = null))
            rooms.setStatus(id, LiveStatus.LIVE, TaskStatus.RECORDING, 1)
            val activeId = sessions.insert(RecordingSessionEntity(roomId = id, roomName = "qa", status = TaskStatus.RECORDING))
            rooms.finishInterruptedStatus(id, TaskStatus.PAUSED, 2)
            assertEquals(TaskStatus.RECORDING, rooms.getById(id)!!.taskStatus)
            sessions.update(sessions.getById(activeId)!!.copy(status = TaskStatus.COMPLETED))
            rooms.finishInterruptedStatus(id, TaskStatus.PAUSED, 3)
            assertEquals(TaskStatus.PAUSED, rooms.getById(id)!!.taskStatus)
            rooms.finishInterruptedStatus(id, TaskStatus.FAILED, 4)
            assertEquals(TaskStatus.PAUSED, rooms.getById(id)!!.taskStatus)
            rooms.setStatus(id, LiveStatus.LIVE, TaskStatus.RECONNECTING, 5)
            rooms.finishInterruptedStatus(id, TaskStatus.FAILED, 6)
            assertEquals(TaskStatus.FAILED, rooms.getById(id)!!.taskStatus)
        } finally { db.close() }
    }
    @Test fun enablingAutomaticRecordingResumesPausedRoomWithoutInterruptingActiveRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.liveRoomDao()
            val id = dao.insert(LiveRoomEntity(originalUrl = "qa", normalizedUrl = "qa", platform = "qa", anchorName = "qa", roomTitle = null))
            dao.setStatus(id, LiveStatus.LIVE, TaskStatus.PAUSED, 1)
            dao.setScheduledMonitoringEnabled(id, true, 2)
            assertEquals(TaskStatus.WAITING, dao.getById(id)!!.taskStatus)
            assertEquals(null, dao.getById(id)!!.lastCheckAt)

            for (status in listOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING, TaskStatus.FINALIZING)) {
                dao.setStatus(id, LiveStatus.LIVE, status, 1)
                dao.setScheduledMonitoringEnabled(id, true, 2)
                assertEquals(status, dao.getById(id)!!.taskStatus)
                dao.setScheduledMonitoringEnabled(id, false, 3)
                assertEquals(status, dao.getById(id)!!.taskStatus)
            }
        } finally { db.close() }
    }
    @Test fun delayedCheckCannotOverwritePauseOrActiveRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.liveRoomDao()
            val id = dao.insert(LiveRoomEntity(originalUrl = "qa", normalizedUrl = "qa", platform = "qa", anchorName = "qa", roomTitle = null))
            for (status in listOf(TaskStatus.PAUSED, TaskStatus.RECORDING, TaskStatus.FINALIZING)) {
                dao.setStatus(id, LiveStatus.LIVE, status, 1)
                dao.setCheckStatus(id, LiveStatus.CHECKING, TaskStatus.CHECKING, 2)
                dao.setCheckStatus(id, LiveStatus.ERROR, TaskStatus.FAILED, 3)
                assertEquals(status, dao.getById(id)!!.taskStatus)
            }
        } finally { db.close() }
    }

    @Test fun retryCandidatesIncludeOnlyFailedSavesWithRecoverySources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StreamCapDatabase::class.java).build()
        try {
            val dao = db.recordingSessionDao()
            val eligible = dao.insert(RecordingSessionEntity(roomId = 1, roomName = "qa", status = TaskStatus.FAILED,
                temporaryPath = "/isolated-test", errorMessage = "文件发布失败：ENOSPC"))
            dao.insert(RecordingSessionEntity(roomId = 1, roomName = "qa", status = TaskStatus.FAILED,
                temporaryPath = "/isolated-test", errorMessage = "直播源不可用"))
            dao.insert(RecordingSessionEntity(roomId = 1, roomName = "qa", status = TaskStatus.PAUSED,
                temporaryPath = "/isolated-test", errorMessage = "文件发布失败：ENOSPC"))
            assertEquals(listOf(eligible), dao.getFailedPublications().map { it.id })
        } finally { db.close() }
    }
}
