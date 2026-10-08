package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Schedule

interface ScheduleReminderSchedulerContract {
    fun schedule(schedule: Schedule): Unit =
        error("Android scheduling requires suspend scheduleCurrent under global/module admission")
    suspend fun scheduleCurrent(schedule: Schedule) = schedule(schedule)
    fun cancel(scheduleId: Long)
    fun cancelObsolete(currentIds: Set<Long>) = Unit
}
