package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionReminderDeliveryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-07T01:05:00Z")
    private val subscription = Subscription(
        id = 42L, appName = "音乐", amountMinor = 1800L, billingCycle = BillingCycle.MONTHLY,
        nextBillingDate = LocalDate.of(2026, 10, 10), startDate = LocalDate.of(2026, 9, 10),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test
    fun `invalid old broadcast preserves current pending nine am alarm at nine oh five`() {
        val alarms = PendingAlarmState()
        val shown = mutableListOf<Subscription>()
        SubscriptionReminderDelivery.reconcile(
            42L, subscription, setOf(3), 3, LocalDate.of(2026, 9, 10), now, zone, alarms, shown::add,
        )
        assertEquals(LocalDate.of(2026, 10, 10), alarms.pending?.dueDate)
        assertEquals(Instant.parse("2026-10-07T01:00:00Z"), alarms.pending?.triggerAt)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `valid delivery consumes current offset before scheduling next cycle and posts current data`() {
        val alarms = PendingAlarmState()
        val shown = mutableListOf<Subscription>()
        SubscriptionReminderDelivery.reconcile(
            42L, subscription, setOf(3), 3, LocalDate.of(2026, 10, 10), now, zone, alarms, shown::add,
        )
        assertEquals(LocalDate.of(2026, 11, 10), alarms.pending?.dueDate)
        assertEquals(Instant.parse("2026-11-07T01:00:00Z"), alarms.pending?.triggerAt)
        assertEquals(listOf(subscription), shown)
    }

    @Test
    fun `deleted subscription clears pending alarm without posting`() {
        val alarms = PendingAlarmState()
        val shown = mutableListOf<Subscription>()
        SubscriptionReminderDelivery.reconcile(
            42L, null, emptySet(), 3, LocalDate.of(2026, 10, 10), now, zone, alarms, shown::add,
        )
        assertNull(alarms.pending)
        assertTrue(shown.isEmpty())
    }

    // Model the external alarm state; use the real time/preservation rules when applying scheduling.
    // The stale-broadcast regression fails if production cancels the offset before validating delivery.
    private inner class PendingAlarmState : SubscriptionReminderSchedulerContract {
        var pending: SubscriptionReminderOccurrence? = SubscriptionReminderOccurrence(
            LocalDate.of(2026, 10, 10), Instant.parse("2026-10-07T01:00:00Z"),
        )

        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) {
            if (!SubscriptionReminderRules.shouldPreservePending(subscription, reminderDays, 3, pending, now, zone)) {
                pending = if (3 in reminderDays) SubscriptionReminderRules.nextReminder(subscription, 3, now, zone) else null
            }
        }

        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) {
            if (subscriptionId == 42L && 3 in reminderDays) pending = null
        }

        override fun cancelAll(subscriptionId: Long) {
            if (subscriptionId == 42L) pending = null
        }
    }
}
