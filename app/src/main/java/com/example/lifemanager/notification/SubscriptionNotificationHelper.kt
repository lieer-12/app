package com.example.lifemanager.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.model.Subscription
import java.time.LocalDate

object SubscriptionNotificationHelper {
    const val CHANNEL_ID = "subscription_reminders"

    fun showReminder(context: Context, subscription: Subscription, daysBefore: Int, dueDate: LocalDate) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "订阅扣费提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val openIntent = PendingIntent.getActivity(
            context,
            ReminderKey.forSubscription(subscription.id, daysBefore),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse(ReminderKey.subscriptionData(subscription.id, daysBefore, dueDate)))
                .putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("订阅扣费提醒")
            .setContentText("${subscription.appName} 将于 $dueDate 扣费（提前 $daysBefore 天）")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        // Notification tags, like alarm data, preserve the full ID and isolate other modules.
        manager.notify(notificationTag(subscription.id, daysBefore), 0, notification)
    }

    fun cancelAll(context: Context, subscriptionId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        SubscriptionReminderRules.supportedDays.forEach { days ->
            manager.cancel(notificationTag(subscriptionId, days), 0)
        }
    }

    private fun notificationTag(subscriptionId: Long, daysBefore: Int) = "subscription/$subscriptionId/$daysBefore"
}
