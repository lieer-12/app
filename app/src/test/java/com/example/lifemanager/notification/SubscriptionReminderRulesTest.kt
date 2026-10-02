package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionReminderRulesTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `selected offsets independently trigger at local nine`() {
        val subscription = subscription("2026-10-10")
        val now = Instant.parse("2026-10-01T00:00:00Z")
        val expected = mapOf(
            1 to Instant.parse("2026-10-09T01:00:00Z"),
            3 to Instant.parse("2026-10-07T01:00:00Z"),
            7 to Instant.parse("2026-10-03T01:00:00Z"),
        )
        expected.forEach { (days, triggerAt) ->
            val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(subscription, days, now, shanghai))
            assertEquals(LocalDate.of(2026, 10, 10), reminder.dueDate)
            assertEquals(triggerAt, reminder.triggerAt)
        }
    }

    @Test
    fun `near billing date keeps future offsets and moves passed offsets to next cycle`() {
        val subscription = subscription("2026-10-10")
        val now = Instant.parse("2026-10-08T00:00:00Z")
        val expected = mapOf(
            1 to (LocalDate.of(2026, 10, 10) to Instant.parse("2026-10-09T01:00:00Z")),
            3 to (LocalDate.of(2026, 11, 10) to Instant.parse("2026-11-07T01:00:00Z")),
            7 to (LocalDate.of(2026, 11, 10) to Instant.parse("2026-11-03T01:00:00Z")),
        )
        expected.forEach { (days, want) ->
            val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(subscription, days, now, shanghai))
            assertEquals(want.first, reminder.dueDate)
            assertEquals(want.second, reminder.triggerAt)
        }
    }

    @Test
    fun `before nine on reminder day still schedules today`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10"), 3, Instant.parse("2026-10-07T00:59:59Z"), shanghai,
        ))
        assertEquals(Instant.parse("2026-10-07T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `at nine moves to strictly future occurrence without immediately firing`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10"), 3, Instant.parse("2026-10-07T01:00:00Z"), shanghai,
        ))
        assertEquals(LocalDate.of(2026, 11, 10), reminder.dueDate)
        assertEquals(Instant.parse("2026-11-07T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `past anchor seeks next cycle without scheduling historical reminders`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2020-01-31"), 7, Instant.parse("2026-10-26T02:00:00Z"), shanghai,
        ))
        assertEquals(LocalDate.of(2026, 11, 30), reminder.dueDate)
        assertEquals(Instant.parse("2026-11-23T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `month end recurrence returns to original anchor after February`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-01-31"), 3, Instant.parse("2026-02-26T02:00:00Z"), shanghai,
        ))
        assertEquals(LocalDate.of(2026, 3, 31), reminder.dueDate)
        assertEquals(Instant.parse("2026-03-28T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `weekly seven day reminder advances even when billing is today`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10", BillingCycle.WEEKLY), 7,
            Instant.parse("2026-10-10T02:00:00Z"), shanghai,
        ))
        assertEquals(LocalDate.of(2026, 10, 24), reminder.dueDate)
        assertEquals(Instant.parse("2026-10-17T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `quarterly reminder preserves month end anchor`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-01-31", BillingCycle.QUARTERLY), 7,
            Instant.parse("2026-04-24T02:00:00Z"), shanghai,
        ))
        assertEquals(LocalDate.of(2026, 7, 31), reminder.dueDate)
        assertEquals(Instant.parse("2026-07-24T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `yearly reminder restores leap day anchor`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2024-02-29", BillingCycle.YEARLY), 1,
            Instant.parse("2027-02-28T02:00:00Z"), shanghai,
        ))
        assertEquals(LocalDate.of(2028, 2, 29), reminder.dueDate)
        assertEquals(Instant.parse("2028-02-28T01:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `inactive subscription has no reminder`() {
        assertNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10").copy(isActive = false), 3,
            Instant.parse("2026-10-01T00:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `occurrence beyond cancellation date has no reminder`() {
        assertNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10").copy(cancelDate = LocalDate.of(2026, 10, 9)), 3,
            Instant.parse("2026-10-01T00:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `unsupported reminder offset has no alarm`() {
        assertNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-10-10"), 2, Instant.parse("2026-10-01T00:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `timezone change keeps nine local while changing trigger instant`() {
        val subscription = subscription("2026-10-10")
        val now = Instant.parse("2026-10-01T00:00:00Z")
        assertEquals(Instant.parse("2026-10-07T01:00:00Z"),
            requireNotNull(SubscriptionReminderRules.nextReminder(subscription, 3, now, shanghai)).triggerAt)
        assertEquals(Instant.parse("2026-10-07T13:00:00Z"),
            requireNotNull(SubscriptionReminderRules.nextReminder(subscription, 3, now, ZoneId.of("America/New_York"))).triggerAt)
    }

    @Test
    fun `reminder across daylight saving uses calendar days`() {
        val reminder = requireNotNull(SubscriptionReminderRules.nextReminder(
            subscription("2026-11-03"), 1, Instant.parse("2026-10-30T00:00:00Z"), ZoneId.of("America/New_York"),
        ))
        assertEquals(Instant.parse("2026-11-02T14:00:00Z"), reminder.triggerAt)
    }

    @Test
    fun `current selected due date is accepted at scheduled time and when delayed same day`() {
        val subscription = subscription("2026-10-10")
        for (now in listOf("2026-10-07T01:00:00Z", "2026-10-07T07:00:00Z")) {
            assertTrue(SubscriptionReminderRules.matchesCurrentReminder(
                subscription, setOf(3), 3, LocalDate.of(2026, 10, 10), Instant.parse(now), shanghai,
            ))
        }
    }

    @Test
    fun `deleted subscription payload is rejected`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            null, setOf(3), 3, LocalDate.of(2026, 10, 10), Instant.parse("2026-10-07T01:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `cancelled subscription payload is rejected`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            subscription("2026-10-10").copy(isActive = false), setOf(3), 3,
            LocalDate.of(2026, 10, 10), Instant.parse("2026-10-07T01:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `removed reminder offset is rejected`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            subscription("2026-10-10"), setOf(1, 7), 3, LocalDate.of(2026, 10, 10),
            Instant.parse("2026-10-07T01:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `edited due date rejects stale payload`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            subscription("2026-10-11"), setOf(3), 3, LocalDate.of(2026, 10, 10),
            Instant.parse("2026-10-07T01:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `edited billing cycle rejects stale occurrence`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            subscription("2026-09-10", BillingCycle.WEEKLY), setOf(3), 3,
            LocalDate.of(2026, 10, 10), Instant.parse("2026-10-07T01:00:00Z"), shanghai,
        ))
    }

    @Test
    fun `early delivery is rejected`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            subscription("2026-10-10"), setOf(3), 3, LocalDate.of(2026, 10, 10),
            Instant.parse("2026-10-07T00:59:59Z"), shanghai,
        ))
    }

    @Test
    fun `old cycle payload is rejected on later reminder day`() {
        assertFalse(SubscriptionReminderRules.matchesCurrentReminder(
            subscription("2026-10-10"), setOf(3), 3, LocalDate.of(2026, 10, 10),
            Instant.parse("2026-11-07T01:00:00Z"), shanghai,
        ))
    }

    private fun subscription(dueDate: String, cycle: BillingCycle = BillingCycle.MONTHLY) = Subscription(
        id = 42L,
        appName = "音乐",
        amountMinor = 1800L,
        billingCycle = cycle,
        nextBillingDate = LocalDate.parse(dueDate),
        startDate = LocalDate.parse(dueDate).minusMonths(1),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
