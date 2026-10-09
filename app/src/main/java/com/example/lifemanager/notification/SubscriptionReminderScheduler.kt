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
import com.example.lifemanager.domain.maintenance.DataGeneration

class SubscriptionReminderScheduler(
    context: Context,
    private val stateProvider: () -> ReminderSchedulingState = { ReminderSchedulingState.fromApplication(context) },
) : SubscriptionReminderSchedulerContract {
    internal var nowProvider: () -> Instant = Instant::now
    internal var zoneProvider: () -> ZoneId = ZoneId::systemDefault
    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)
    // Operational alarm identities only. Subscriptions and selected offsets are always read from Room.
    // Persist before setting an alarm so cancellation still works after process death or a date edit.
    private val alarmIndex = this.context.getSharedPreferences("subscription_alarm_identities", Context.MODE_PRIVATE)

    override suspend fun scheduleCurrent(subscription: Subscription, reminderDays: Set<Int>) {
        val (generation, settings) = stateProvider().read()
        if (!settings.subscriptionReminders) {
            cancelAll(subscription.id)
            ReminderNotifications(context).clearModule("subscription")
            return
        }
        synchronized(lock) {
        if (subscription.id <= 0L || !subscription.isActive) {
            cancelAll(subscription.id)
            return@synchronized
        }
        val now = nowProvider()
        val zone = zoneProvider()
        SubscriptionReminderRules.supportedDays.forEach { days ->
            if (SubscriptionReminderRules.shouldPreservePending(
                    subscription, reminderDays, days, existingPending(subscription.id, days, generation), now, zone,
                )
            ) return@forEach
            // Reconciliation must leave today's valid inexact alarm awaiting delivery untouched.
            // All other replacements (including removed offsets) cancel the old identity first.
            val recover = if (alarmIndex.getBoolean(receivedSlot(subscription.id, days), false) &&
                alarmIndex.getLong(generationSlot(subscription.id, days), -1) == generation.value) {
                alarmIndex.getString(slot(subscription.id, days), null)?.let(LocalDate::parse)?.takeIf {
                    !wasDelivered(context, subscription.id, days, it, generation) &&
                        SubscriptionReminderRules.matchesCurrentReminder(subscription, reminderDays, days, it, now, zone)
                }?.let { SubscriptionReminderOccurrence(it, it.minusDays(days.toLong()).atTime(9, 0).atZone(zone).toInstant()) }
            } else null
            if (recover == null) cancel(subscription.id, setOf(days))
            else cancelAlarm(subscription.id, days)
            if (days !in reminderDays) return@forEach
            val occurrence = recover ?: SubscriptionReminderRules.nextReminder(subscription, days, now, zone)
                ?: return@forEach
            check(alarmIndex.edit()
                .putString(slot(subscription.id, days), occurrence.dueDate.toString())
                .putLong(triggerSlot(subscription.id, days), occurrence.triggerAt.toEpochMilli())
                .putLong(generationSlot(subscription.id, days), generation.value)
                .putBoolean(receivedSlot(subscription.id, days), true)
                .commit()
            ) {
                "Unable to persist subscription alarm identity"
            }
            val operation = pendingIntent(subscription.id, days, occurrence.dueDate, PendingIntent.FLAG_UPDATE_CURRENT, generation)
                ?: return@forEach
            try {
                alarmManager.setReminder(maxOf(occurrence.triggerAt.toEpochMilli(), now.toEpochMilli() + 1_000), operation)
                check(alarmIndex.edit().putBoolean(receivedSlot(subscription.id, days), false).commit()) {
                    "Unable to persist accepted alarm generation"
                }
            } catch (error: Exception) {
                // A created PendingIntent is not evidence that AlarmManager accepted the alarm.
                operation.cancel()
                // Retain the qualified receipt so a fresh Room pass can retry today's occurrence.
                throw error
            }
        }
        }
    }

    override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) = synchronized(lock) {
        reminderDays.intersect(SubscriptionReminderRules.supportedDays).forEach { days ->
            val key = slot(subscriptionId, days)
            cancelAlarm(subscriptionId, days)
            check(alarmIndex.edit().remove(key).remove(triggerSlot(subscriptionId, days)).remove(generationSlot(subscriptionId, days)).remove(receivedSlot(subscriptionId, days)).commit()) {
                "Unable to remove subscription alarm identity"
            }
        }
    }

    override fun cancelAll(subscriptionId: Long) = synchronized(lock) {
        cancel(subscriptionId, SubscriptionReminderRules.supportedDays)
        SubscriptionNotificationHelper.cancelAll(context, subscriptionId)
    }

    override fun cancelObsolete(currentIds: Set<Long>) = synchronized(lock) {
        (alarmIndex.all.keys.mapNotNull { it.substringBefore('/').toLongOrNull() }.toSet() - currentIds)
            .forEach(::cancelAll)
    }

    private fun existingPending(subscriptionId: Long, days: Int, generation: DataGeneration): SubscriptionReminderOccurrence? {
        // Old/legacy cached identities must never preserve an alarm across a data replacement.
        if (alarmIndex.getBoolean(receivedSlot(subscriptionId, days), false) || !alarmIndex.contains(generationSlot(subscriptionId, days)) ||
            alarmIndex.getLong(generationSlot(subscriptionId, days), -1L) != generation.value) return null
        val dueDate = alarmIndex.getString(slot(subscriptionId, days), null)?.let(LocalDate::parse) ?: return null
        if (wasDelivered(context, subscriptionId, days, dueDate, generation)) return null
        val triggerKey = triggerSlot(subscriptionId, days)
        if (!alarmIndex.contains(triggerKey) ||
            pendingIntent(subscriptionId, days, dueDate, PendingIntent.FLAG_NO_CREATE) == null
        ) return null
        return SubscriptionReminderOccurrence(dueDate, Instant.ofEpochMilli(alarmIndex.getLong(triggerKey, 0L)))
    }

    private fun cancelAlarm(subscriptionId: Long, days: Int) {
        val dueDate = alarmIndex.getString(slot(subscriptionId, days), null)?.let(LocalDate::parse) ?: return
        pendingIntent(subscriptionId, days, dueDate, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntent(subscriptionId: Long, days: Int, dueDate: LocalDate, flags: Int, generation: DataGeneration? = null): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            ReminderKey.forSubscription(subscriptionId, days),
            Intent(context, SubscriptionReminderReceiver::class.java)
                .setData(Uri.parse(ReminderKey.subscriptionData(subscriptionId, days, dueDate)))
                .putExtra(SubscriptionReminderReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                .putExtra(SubscriptionReminderReceiver.EXTRA_DAYS_BEFORE, days)
                .putExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE, dueDate.toString())
                .apply { if (generation != null) putExtra(ReminderGeneration.EXTRA, generation.value) },
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun slot(subscriptionId: Long, days: Int) = "$subscriptionId/$days"
    private fun triggerSlot(subscriptionId: Long, days: Int) = "$subscriptionId/$days/trigger"
    private fun generationSlot(subscriptionId: Long, days: Int) = "$subscriptionId/$days/generation"
    private fun receivedSlot(subscriptionId: Long, days: Int) = "$subscriptionId/$days/received"

    override fun acknowledgeArrival(subscriptionId: Long, daysBefore: Int, dueDate: LocalDate, generation: DataGeneration): Unit = synchronized(lock) {
        recordArrival(context, subscriptionId, daysBefore, dueDate, generation)
    }

    companion object {
        // The worker and Hilt can create different instances; serialize their cancel/replace operations.
        private val lock = Any()

        /** Matching operational receipt only: no scheduler resolution, Room, or AlarmManager work. */
        internal fun recordArrival(context: Context, id: Long, days: Int, dueDate: LocalDate, generation: DataGeneration): Unit = synchronized(lock) {
            val index = context.applicationContext.getSharedPreferences("subscription_alarm_identities", Context.MODE_PRIVATE)
            if (index.getLong("$id/$days/generation", -1) == generation.value &&
                index.getString("$id/$days", null) == dueDate.toString()) {
                check(index.edit().putBoolean("$id/$days/received", true).commit())
            }
        }

        internal fun wasDelivered(context: Context, id: Long, days: Int, dueDate: LocalDate, generation: DataGeneration): Boolean = synchronized(lock) {
            val index = context.applicationContext.getSharedPreferences("subscription_alarm_identities", Context.MODE_PRIVATE)
            index.getLong("$id/$days/delivered-generation", -1) == generation.value &&
                index.getString("$id/$days/delivered-date", null) == dueDate.toString()
        }

        /** Caller holds the module lock; a queued replacement must not alert twice after posting. */
        internal fun recordDelivery(context: Context, id: Long, days: Int, dueDate: LocalDate, generation: DataGeneration): Unit = synchronized(lock) {
            val index = context.applicationContext.getSharedPreferences("subscription_alarm_identities", Context.MODE_PRIVATE)
            check(index.edit().putLong("$id/$days/delivered-generation", generation.value)
                .putString("$id/$days/delivered-date", dueDate.toString()).commit())
        }
    }
}
