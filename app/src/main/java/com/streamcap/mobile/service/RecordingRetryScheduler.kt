package com.streamcap.mobile.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Accessed on the service's main dispatcher, including cancellation and callbacks. */
internal class RecordingRetryScheduler(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<Long, Job>()

    fun isPending(roomId: Long) = jobs.containsKey(roomId)
    fun hasPending() = jobs.isNotEmpty()
    fun cancel(roomId: Long) { jobs.remove(roomId)?.cancel() }

    fun schedule(roomId: Long, retryCount: Int, retry: suspend () -> Unit) {
        cancel(roomId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMillis(retryCount))
            jobs.remove(roomId)
            retry()
        }
        jobs[roomId] = job
        job.invokeOnCompletion { if (jobs[roomId] === job) jobs.remove(roomId) }
        job.start()
    }

    companion object {
        fun delayMillis(retryCount: Int): Long =
            (2_000L * (1L shl retryCount.coerceIn(0, 4))).coerceAtMost(30_000L)
    }
}
