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
    fun `monthly next billing restores anchor day after February`() {
        val subscription = subscription(
            nextBillingDate = LocalDate.of(2026, 1, 31),
            startDate = LocalDate.of(2026, 1, 31),
        )

        assertEquals(
            LocalDate.of(2026, 3, 31),
            SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 3, 1)),
        )
    }

    @Test
    fun `quarterly next billing restores anchor day after April`() {
        val subscription = subscription(
            billingCycle = BillingCycle.QUARTERLY,
            nextBillingDate = LocalDate.of(2026, 1, 31),
            startDate = LocalDate.of(2026, 1, 31),
        )

        assertEquals(
            LocalDate.of(2026, 7, 31),
            SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 7, 1)),
        )
    }

    @Test
    fun `yearly next billing restores leap day in leap year`() {
        val subscription = subscription(
            billingCycle = BillingCycle.YEARLY,
            nextBillingDate = LocalDate.of(2024, 2, 29),
            startDate = LocalDate.of(2024, 2, 29),
        )

        assertEquals(
            LocalDate.of(2028, 2, 29),
            SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2028, 2, 1)),
        )
    }

    @Test
    fun `January forecast derives historical dates from March anchor`() {
        val subscription = subscription(
            nextBillingDate = LocalDate.of(2026, 3, 31),
            startDate = LocalDate.of(2026, 1, 31),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 1, 31)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
        )
    }

    @Test
    fun `forecast excludes March anchor occurrence beyond range end`() {
        val subscription = subscription(
            nextBillingDate = LocalDate.of(2026, 1, 31),
            startDate = LocalDate.of(2026, 1, 31),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 2, 28)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 30)),
        )
    }

    @Test
    fun `historical quarterly forecast retains signed anchor cycles`() {
        val subscription = subscription(
            billingCycle = BillingCycle.QUARTERLY,
            nextBillingDate = LocalDate.of(2026, 7, 31),
            startDate = LocalDate.of(2026, 1, 31),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
        )
    }

    @Test
    fun `historical yearly forecast restores leap day`() {
        val subscription = subscription(
            billingCycle = BillingCycle.YEARLY,
            nextBillingDate = LocalDate.of(2028, 2, 29),
            startDate = LocalDate.of(2024, 2, 29),
        )

        assertEquals(
            listOf(LocalDate.of(2024, 2, 29)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)),
        )
    }

    @Test
    fun `forecast includes start and cancellation dates but excludes surrounding cycles`() {
        val subscription = subscription(
            nextBillingDate = LocalDate.of(2026, 3, 31),
            startDate = LocalDate.of(2026, 2, 28),
            cancelDate = LocalDate.of(2026, 3, 31),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30)),
        )
        assertEquals(
            LocalDate.of(2026, 3, 31),
            SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 3, 31)),
        )
        assertNull(SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 4, 1)))
    }

    @Test
    fun `effective next billing never precedes original anchor`() {
        val subscription = subscription(nextBillingDate = LocalDate.of(2026, 3, 31))

        assertEquals(
            LocalDate.of(2026, 3, 31),
            SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `weekly forecasts seek directly from an ancient anchor`() {
        val subscription = subscription(
            billingCycle = BillingCycle.WEEKLY,
            nextBillingDate = LocalDate.of(1, 1, 1),
            startDate = LocalDate.of(1, 1, 1),
        )

        assertEquals(
            LocalDate.of(2026, 3, 2),
            SubscriptionRules.effectiveNextBillingDate(subscription, LocalDate.of(2026, 3, 1)),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 3, 2)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7)),
        )
    }

    @Test
    fun `weekly historical forecast includes exact range boundaries`() {
        val subscription = subscription(
            billingCycle = BillingCycle.WEEKLY,
            nextBillingDate = LocalDate.of(2026, 3, 16),
            startDate = LocalDate.of(2026, 3, 2),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 9)),
            SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 9)),
        )
        assertTrue(SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 2)).isEmpty())
    }

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
        assertTrue(SubscriptionRules.forecastOccurrences(subscription, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31)).isEmpty())
    }

    @Test
    fun `validation rejects a blank name and non positive amount`() {
        assertEquals("订阅名称不能为空", SubscriptionRules.validate(subscription(appName = "  ")))
        assertEquals("金额必须大于 0", SubscriptionRules.validate(subscription(amountMinor = 0)))
    }

    @Test
    fun `validation accepts ISO currency codes and rejects malformed or unknown codes`() {
        listOf("CNY", "USD", "JPY", "KWD").forEach { currency ->
            assertNull(SubscriptionRules.validate(subscription(currency = currency)), currency)
        }
        listOf("", "CN", "cny", " CNY ", "ZZZ").forEach { currency ->
            assertEquals("币种必须为有效的 ISO 4217 代码", SubscriptionRules.validate(subscription(currency = currency)), currency)
        }
    }

    @Test
    fun `money parsing converts decimal strings exactly using currency minor units`() {
        assertEquals(29L, SubscriptionRules.parseAmountMinor("0.29"))
        assertEquals(1_234L, SubscriptionRules.parseAmountMinor(" 12.340 "))
        assertEquals(123L, SubscriptionRules.parseAmountMinor("123", "JPY"))
        assertEquals(1_234L, SubscriptionRules.parseAmountMinor("1.234", "KWD"))
        assertEquals(Long.MAX_VALUE, SubscriptionRules.parseAmountMinor("92233720368547758.07"))
    }

    @Test
    fun `money parsing rejects invalid precision non positive amounts and overflow`() {
        listOf("", "abc", "0", "-1", "1.005", "92233720368547758.08").forEach { amount ->
            assertNull(SubscriptionRules.parseAmountMinor(amount), amount)
        }
        assertNull(SubscriptionRules.parseAmountMinor("1.5", "JPY"))
        assertNull(SubscriptionRules.parseAmountMinor("1", "ZZZ"))
        assertNull(SubscriptionRules.parseAmountMinor("1", "XXX"))
    }

    private fun subscription(
        appName: String = "音乐服务",
        amountMinor: Long = 1_500,
        currency: String = "CNY",
        billingCycle: BillingCycle = BillingCycle.MONTHLY,
        nextBillingDate: LocalDate = LocalDate.of(2026, 8, 25),
        startDate: LocalDate = LocalDate.of(2026, 1, 25),
        isActive: Boolean = true,
        cancelDate: LocalDate? = null,
        note: String? = null,
    ) = Subscription(
        id = 7,
        appName = appName,
        amountMinor = amountMinor,
        currency = currency,
        billingCycle = billingCycle,
        nextBillingDate = nextBillingDate,
        startDate = startDate,
        category = "影音",
        note = note,
        isActive = isActive,
        cancelDate = cancelDate,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
