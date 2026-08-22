package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Schedule

interface ScheduleReminderSchedulerContract {
    fun schedule(schedule: Schedule)
    fun cancel(scheduleId: Long)
}
