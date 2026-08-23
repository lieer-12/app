package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionPayment
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionRulesTest {
    @Test
    fun `monthly forecast keeps actual payments separate`() {
        val august = LocalDate.of(2026, 8, 23)
        val subscription = subscription(nextBillingDate = LocalDate.of(2026, 8, 25))
        val payment = SubscriptionPayment(
            id = 1,
            subscriptionId = subscription.id,
            amountMinor = 1_200,
            currency = "CNY",
            paidAt = LocalDate.of(2026, 8, 10),
            note = null,
        )

        val stats = SubscriptionRules.calculateStats(listOf(subscription), listOf(payment), august)

        assertEquals(1_500, stats.currentMonth.forecastMinor)
        assertEquals(1_200, stats.currentMonth.actualMinor)
    }

    @Test
    fun `non CNY values are excluded from statistics but retained by csv`() {
        val usd = subscription(currency = "USD", amountMinor = 999)
        val stats = SubscriptionRules.calculateStats(listOf(usd), emptyList(), LocalDate.of(2026, 8, 23))
        val csv = SubscriptionRules.exportCsv(listOf(usd), emptyList())

        assertEquals(0, stats.currentMonth.forecastMinor)
        assertTrue(csv.contains("subscription"))
        assertTrue(csv.contains("USD"))
    }

    @Test
    fun `csv escapes commas quotes and newlines`() {
        val subscription = subscription(note = "a,\"b\"\nc")

        val csv = SubscriptionRules.exportCsv(listOf(subscription), emptyList())

        assertTrue(csv.contains("\"a,\"\"b\"\"\nc\""))
    }

    @Test
    fun `cancelled subscription has no future forecast or next billing date`() {
        val subscription = subscription(isActive = false, cancelDate = LocalDate.of(2026, 8, 1))

        assertNull(SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 8, 23)))
        assertFalse(SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)).isNotEmpty())
    }

    @Test
    fun `validation rejects a blank name and non positive amount`() {
        assertEquals("订阅名称不能为空", SubscriptionRules.validate(subscription(appName = "  ")))
        assertEquals("金额必须大于 0", SubscriptionRules.validate(subscription(amountMinor = 0)))
    }

    private fun subscription(
        appName: String = "音乐服务",
        amountMinor: Long = 1_500,
        currency: String = "CNY",
        nextBillingDate: LocalDate = LocalDate.of(2026, 8, 25),
        isActive: Boolean = true,
        cancelDate: LocalDate? = null,
        note: String? = null,
    ) = Subscription(
        id = 7,
        appName = appName,
        amountMinor = amountMinor,
        currency = currency,
        billingCycle = BillingCycle.MONTHLY,
        nextBillingDate = nextBillingDate,
        startDate = LocalDate.of(2026, 1, 25),
        category = "影音",
        note = note,
        isActive = isActive,
        cancelDate = cancelDate,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
