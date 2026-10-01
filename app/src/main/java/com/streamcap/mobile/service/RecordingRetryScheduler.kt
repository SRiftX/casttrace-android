package com.streamcap.mobile.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reservations are shared by service commands and Default-dispatcher callbacks. */
internal class RecordingRetryScheduler(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<Long, Job>()

    fun isPending(roomId: Long) = synchronized(jobs) { jobs.containsKey(roomId) }
    fun hasPending() = synchronized(jobs) { jobs.isNotEmpty() }
    fun cancel(roomId: Long) { synchronized(jobs) { jobs.remove(roomId)?.cancel() } }

    fun schedule(roomId: Long, retryCount: Int, retry: suspend () -> Unit) {
        synchronized(jobs) {
            cancel(roomId)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMillis(retryCount))
                val self = kotlin.coroutines.coroutineContext[Job]
                val ownsReservation = synchronized(jobs) {
                    if (jobs[roomId] === self) { jobs.remove(roomId); true } else false
                }
                if (ownsReservation) retry()
            }
            jobs[roomId] = job
            job.invokeOnCompletion {
                synchronized(jobs) { if (jobs[roomId] === job) jobs.remove(roomId) }
            }
            job.start()
        }
    }

    companion object {
        fun delayMillis(retryCount: Int): Long =
            (2_000L * (1L shl retryCount.coerceIn(0, 4))).coerceAtMost(30_000L)
    }
}
