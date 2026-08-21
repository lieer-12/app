package com.example.lifemanager.notification

import java.time.Instant

interface ReminderSchedulerContract {
    fun schedule(todoId: Long, title: String, dueAt: Instant)
    fun cancel(todoId: Long)
}
