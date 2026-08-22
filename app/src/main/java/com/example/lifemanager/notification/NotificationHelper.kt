package com.example.lifemanager.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.lifemanager.MainActivity

object NotificationHelper {
    const val CHANNEL_ID = "todo_reminders"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(com.example.lifemanager.R.string.notification_channel_todos),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(com.example.lifemanager.R.string.notification_channel_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun showTodoReminder(context: Context, todoId: Long, title: String) {
        showReminder(context, ReminderKey.forTodo(todoId), "待办提醒", title, TodoReminderReceiver.EXTRA_TODO_ID, todoId)
    }

    fun showScheduleReminder(context: Context, scheduleId: Long, title: String) {
        showReminder(context, ReminderKey.forSchedule(scheduleId), "日程提醒", title, ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, scheduleId)
    }

    private fun showReminder(context: Context, notificationId: Int, notificationTitle: String, title: String, extraKey: String, id: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).putExtra(extraKey, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notificationTitle)
            .setContentText(title)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }
}
