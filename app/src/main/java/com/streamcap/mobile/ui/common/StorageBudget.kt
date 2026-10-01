package com.streamcap.mobile.ui.common

data class StorageBudget(
    val freeBytes: Long,
    val activeBytes: Long,
    val activeDurationSeconds: Long,
    val safetyBytes: Long = 128L * 1024L * 1024L,
) {
    val bytesPerSecond: Long
        get() = if (activeDurationSeconds > 0) activeBytes / activeDurationSeconds else 0L
    val estimatedSecondsRemaining: Long?
        get() = bytesPerSecond.takeIf { it > 0 }?.let { ((freeBytes - safetyBytes).coerceAtLeast(0L) / it).coerceAtLeast(0L) }
    val level: StorageBudgetLevel
        get() = when {
            freeBytes <= safetyBytes -> StorageBudgetLevel.CRITICAL
            estimatedSecondsRemaining != null && estimatedSecondsRemaining!! < 15 * 60 -> StorageBudgetLevel.WARNING
            else -> StorageBudgetLevel.NORMAL
        }
}

enum class StorageBudgetLevel { NORMAL, WARNING, CRITICAL }
