package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Android-free delivery flow; caller holds the operation gate from Room reads through notification. */
object SubscriptionReminderDelivery {
    fun reconcile(
        subscriptionId: Long,
        subscription: Subscription?,
        selectedDays: Set<Int>,
        daysBefore: Int,
        dueDate: LocalDate,
        now: Instant,
        zone: ZoneId,
        scheduler: SubscriptionReminderSchedulerContract,
        showReminder: (Subscription) -> Unit,
    ) {
        val valid = SubscriptionReminderRules.matchesCurrentReminder(
            subscription, selectedDays, daysBefore, dueDate, now, zone,
        )
        try {
            // An old payload may share its offset with a different, valid pending alarm today.
            // Only a validated delivery consumes the offset before future reconciliation.
            if (valid) scheduler.cancel(subscriptionId, setOf(daysBefore))
            if (subscription == null) scheduler.cancelAll(subscriptionId)
            else scheduler.schedule(subscription, selectedDays)
        } finally {
            // A failed rearm must not suppress a valid notification; receiver logs the failure.
            if (valid && subscription != null) showReminder(subscription)
        }
    }
}
