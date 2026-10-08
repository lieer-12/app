package com.example.lifemanager.notification

import android.app.NotificationManager
import android.content.Context
import com.example.lifemanager.domain.maintenance.DataGeneration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** API 23+ active notifications only; Android provides no inventory of scheduled alarms. */
class ReminderNotifications @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun clearStale(current: DataGeneration) {
        manager.activeNotifications.filter { notification ->
            @Suppress("DEPRECATION")
            val generation = notification.notification.extras.get(ReminderGeneration.EXTRA) as? Long
            isReminder(notification.notification.channelId) && generation != current.value
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun clearModule(module: String) {
        manager.activeNotifications.filter { notification ->
            val extras = notification.notification.extras
            if (module == "subscription") notification.notification.channelId == SubscriptionNotificationHelper.CHANNEL_ID
            else notification.notification.channelId == NotificationHelper.CHANNEL_ID &&
                (extras.getString(MODULE) == module || !extras.containsKey(MODULE))
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelTodo(id: Long) = manager.cancel(ReminderKey.forTodo(id))
    fun cancelSchedule(id: Long) = manager.cancel(ReminderKey.forSchedule(id))

    private fun isReminder(channel: String?) = channel == NotificationHelper.CHANNEL_ID ||
        channel == SubscriptionNotificationHelper.CHANNEL_ID

    companion object { const val MODULE = "reminder_module" }
}
