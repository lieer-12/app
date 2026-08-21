package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

class ReminderScheduler(private val context: Context) : ReminderSchedulerContract {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(todoId: Long, title: String, dueAt: Instant) {
        val now = System.currentTimeMillis()
        if (dueAt.toEpochMilli() <= now) return
        val triggerAt = maxOf(dueAt.minusSeconds(15 * 60).toEpochMilli(), now + 1_000)
        val pendingIntent = pendingIntent(todoId, title)
        val canUseExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    override fun cancel(todoId: Long) {
        alarmManager.cancel(pendingIntent(todoId, ""))
    }

    private fun pendingIntent(todoId: Long, title: String): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java)
            .putExtra(TodoReminderReceiver.EXTRA_TODO_ID, todoId)
            .putExtra(TodoReminderReceiver.EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context,
            ReminderKey.forTodo(todoId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
