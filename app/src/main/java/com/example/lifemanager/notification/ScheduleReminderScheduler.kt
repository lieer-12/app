package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.usecase.ScheduleRules
import java.time.Instant

class ScheduleReminderScheduler(private val context: Context) : ScheduleReminderSchedulerContract {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(schedule: Schedule) {
        val occurrence = ScheduleRules.nextReminderOccurrence(schedule, Instant.now()) ?: run {
            cancel(schedule.id)
            return
        }
        val triggerAt = occurrence.startAt
            ?.minusSeconds((schedule.reminderMinutes ?: 0) * 60L)
            ?.toEpochMilli()
            ?: return
        val pendingIntent = pendingIntent(schedule.id, schedule.title)
        val canUseExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    override fun cancel(scheduleId: Long) {
        alarmManager.cancel(pendingIntent(scheduleId, ""))
    }

    private fun pendingIntent(scheduleId: Long, title: String): PendingIntent {
        val intent = Intent(context, ScheduleReminderReceiver::class.java)
            .putExtra(ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            .putExtra(ScheduleReminderReceiver.EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context,
            ReminderKey.forSchedule(scheduleId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
