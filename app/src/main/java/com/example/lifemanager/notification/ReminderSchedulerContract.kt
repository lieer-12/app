package com.example.lifemanager.notification

import java.time.Instant

interface ReminderSchedulerContract {
    fun schedule(todoId: Long, title: String, dueAt: Instant): Unit =
        error("Android scheduling requires suspend scheduleCurrent under global/module admission")
    suspend fun scheduleCurrent(todoId: Long, title: String, dueAt: Instant) = schedule(todoId, title, dueAt)
    fun cancel(todoId: Long)
    fun cancelObsolete(currentIds: Set<Long>) = Unit
}
