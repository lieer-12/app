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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            ReminderKey.forTodo(todoId),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TODO_ID, todoId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("待办提醒")
            .setContentText(title)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(ReminderKey.forTodo(todoId), notification)
    }
}
