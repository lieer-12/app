package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionReminderReconciliationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-07T01:05:00Z")
    private val subscription = Subscription(
        id = 42L, appName = "音乐", amountMinor = 1800L, billingCycle = BillingCycle.MONTHLY,
        nextBillingDate = LocalDate.of(2026, 10, 10), startDate = LocalDate.of(2026, 9, 10),
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
    private val pending = SubscriptionReminderOccurrence(
        LocalDate.of(2026, 10, 10), Instant.parse("2026-10-07T01:00:00Z"),
    )

    @Test
    fun `nine oh five reconciliation preserves valid nine am alarm awaiting inexact delivery`() {
        assertTrue(SubscriptionReminderRules.shouldPreservePending(subscription, setOf(3), 3, pending, now, zone))
    }

    @Test
    fun `missing or explicitly consumed pending intent must advance to next occurrence`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(subscription, setOf(3), 3, null, now, zone))
    }

    @Test
    fun `cancelled subscription cannot preserve pending alarm`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(subscription.copy(isActive = false), setOf(3), 3, pending, now, zone))
    }

    @Test
    fun `removed day cannot preserve pending alarm`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(subscription, setOf(1, 7), 3, pending, now, zone))
    }

    @Test
    fun `edited due date cannot preserve stale pending alarm`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(
            subscription.copy(nextBillingDate = LocalDate.of(2026, 10, 11)), setOf(3), 3, pending, now, zone,
        ))
    }

    @Test
    fun `previous reminder day cannot be preserved`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(
            subscription, setOf(3), 3, pending, Instant.parse("2026-10-08T01:05:00Z"), zone,
        ))
    }

    @Test
    fun `future reminder uses normal cancel and replace scheduling`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(
            subscription, setOf(3), 3, pending, Instant.parse("2026-10-07T00:05:00Z"), zone,
        ))
    }

    @Test
    fun `changed timezone cannot preserve an alarm set at wrong local hour`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(
            subscription, setOf(3), 3, pending, Instant.parse("2026-10-07T13:05:00Z"), ZoneId.of("America/New_York"),
        ))
    }

    @Test
    fun `wrong stored trigger time cannot preserve pending alarm`() {
        assertFalse(SubscriptionReminderRules.shouldPreservePending(
            subscription, setOf(3), 3, pending.copy(triggerAt = Instant.parse("2026-10-07T02:00:00Z")), now, zone,
        ))
    }
}
