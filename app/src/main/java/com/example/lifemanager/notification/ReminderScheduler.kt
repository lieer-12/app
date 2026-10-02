package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import java.time.Instant

class ReminderScheduler(private val context: Context) : ReminderSchedulerContract {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(todoId: Long, title: String, dueAt: Instant) {
        cancel(todoId)
        val now = System.currentTimeMillis()
        if (dueAt.toEpochMilli() <= now) return
        val triggerAt = maxOf(dueAt.minusSeconds(15 * 60).toEpochMilli(), now + 1_000)
        val pendingIntent = pendingIntent(todoId, title, dueAt)
        val canUseExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            try { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
            catch (_: SecurityException) { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent) }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    override fun cancel(todoId: Long) {
        // Also cancel the old no-data PendingIntent when upgrading an existing installation.
        listOf(false, true).forEach { currentIdentity ->
            val intent = Intent(context, TodoReminderReceiver::class.java)
            if (currentIdentity) intent.data = Uri.parse("lifemanager://todo-reminder/$todoId")
            PendingIntent.getBroadcast(context, ReminderKey.forTodo(todoId), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }

    private fun pendingIntent(todoId: Long, title: String, dueAt: Instant): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java)
            .setData(Uri.parse("lifemanager://todo-reminder/$todoId"))
            .putExtra(TodoReminderReceiver.EXTRA_TODO_ID, todoId)
            .putExtra(TodoReminderReceiver.EXTRA_TITLE, title)
            .putExtra(TodoReminderReceiver.EXTRA_DUE_AT, dueAt.toEpochMilli())
        return PendingIntent.getBroadcast(
            context,
            ReminderKey.forTodo(todoId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
