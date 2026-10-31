package com.streamcap.mobile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule

abstract class HiltGraphTest {
    @get:Rule(order = Int.MIN_VALUE)
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun injectHiltGraph() {
        hiltRule.inject()
    }
}

internal fun resolvePlaybackRecordingId(context: Context, argumentName: String): Long? = runBlocking {
    val recordings = context.applicationContext.testGraph.repository.recordings.first()
    fun isPlayable(id: Long) = recordings.any { recording ->
        recording.id == id && recording.durationSeconds >= 600 && recording.outputUris.isNotEmpty() &&
            recording.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED)
    }
    val requested = InstrumentationRegistry.getArguments().getString(argumentName)?.toLongOrNull()
    requested?.takeIf(::isPlayable) ?: recordings.asSequence()
        .filter { it.durationSeconds >= 600 && it.outputUris.isNotEmpty() &&
            it.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED) }
        .maxByOrNull { it.id }
        ?.id
}
