package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SubscriptionReminderScheduler(context: Context) : SubscriptionReminderSchedulerContract {
    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)
    // Operational alarm identities only. Subscriptions and selected offsets are always read from Room.
    // Persist before setting an alarm so cancellation still works after process death or a date edit.
    private val alarmIndex = this.context.getSharedPreferences("subscription_alarm_identities", Context.MODE_PRIVATE)

    override fun schedule(subscription: Subscription, reminderDays: Set<Int>) = synchronized(lock) {
        if (subscription.id <= 0L || !subscription.isActive) {
            cancelAll(subscription.id)
            return@synchronized
        }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        SubscriptionReminderRules.supportedDays.forEach { days ->
            if (SubscriptionReminderRules.shouldPreservePending(
                    subscription, reminderDays, days, existingPending(subscription.id, days), now, zone,
                )
            ) return@forEach
            // Reconciliation must leave today's valid inexact alarm awaiting delivery untouched.
            // All other replacements (including removed offsets) cancel the old identity first.
            cancel(subscription.id, setOf(days))
            if (days !in reminderDays) return@forEach
            val occurrence = SubscriptionReminderRules.nextReminder(subscription, days, now, zone)
                ?: return@forEach
            check(alarmIndex.edit()
                .putString(slot(subscription.id, days), occurrence.dueDate.toString())
                .putLong(triggerSlot(subscription.id, days), occurrence.triggerAt.toEpochMilli())
                .commit()
            ) {
                "Unable to persist subscription alarm identity"
            }
            val operation = pendingIntent(subscription.id, days, occurrence.dueDate, PendingIntent.FLAG_UPDATE_CURRENT)
                ?: return@forEach
            setAlarm(occurrence.triggerAt.toEpochMilli(), operation)
        }
    }

    override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) = synchronized(lock) {
        reminderDays.intersect(SubscriptionReminderRules.supportedDays).forEach { days ->
            val key = slot(subscriptionId, days)
            val dueDate = alarmIndex.getString(key, null)?.let(LocalDate::parse)
            if (dueDate != null) {
                pendingIntent(subscriptionId, days, dueDate, PendingIntent.FLAG_NO_CREATE)?.let { operation ->
                    alarmManager.cancel(operation)
                    operation.cancel()
                }
            }
            check(alarmIndex.edit().remove(key).remove(triggerSlot(subscriptionId, days)).commit()) {
                "Unable to remove subscription alarm identity"
            }
        }
    }

    override fun cancelAll(subscriptionId: Long) = synchronized(lock) {
        cancel(subscriptionId, SubscriptionReminderRules.supportedDays)
        SubscriptionNotificationHelper.cancelAll(context, subscriptionId)
    }

    private fun existingPending(subscriptionId: Long, days: Int): SubscriptionReminderOccurrence? {
        val dueDate = alarmIndex.getString(slot(subscriptionId, days), null)?.let(LocalDate::parse) ?: return null
        val triggerKey = triggerSlot(subscriptionId, days)
        if (!alarmIndex.contains(triggerKey) ||
            pendingIntent(subscriptionId, days, dueDate, PendingIntent.FLAG_NO_CREATE) == null
        ) return null
        return SubscriptionReminderOccurrence(dueDate, Instant.ofEpochMilli(alarmIndex.getLong(triggerKey, 0L)))
    }

    private fun setAlarm(triggerAt: Long, operation: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                return
            }
        } catch (_: SecurityException) {
            // Permission can be unavailable or revoked. Preserve reminders using an inexact alarm.
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }

    private fun pendingIntent(subscriptionId: Long, days: Int, dueDate: LocalDate, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            ReminderKey.forSubscription(subscriptionId, days),
            Intent(context, SubscriptionReminderReceiver::class.java)
                .setData(Uri.parse(ReminderKey.subscriptionData(subscriptionId, days, dueDate)))
                .putExtra(SubscriptionReminderReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                .putExtra(SubscriptionReminderReceiver.EXTRA_DAYS_BEFORE, days)
                .putExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE, dueDate.toString()),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun slot(subscriptionId: Long, days: Int) = "$subscriptionId/$days"
    private fun triggerSlot(subscriptionId: Long, days: Int) = "$subscriptionId/$days/trigger"

    companion object {
        // The worker and Hilt can create different instances; serialize their cancel/replace operations.
        private val lock = Any()
    }
}
