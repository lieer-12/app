package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.BillingCycleStats
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionCategoryStats
import com.example.lifemanager.domain.model.SubscriptionMonthStats
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.model.SubscriptionStats
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Currency

object SubscriptionRules {
    private const val CNY = "CNY"

    fun validate(subscription: Subscription): String? = when {
        subscription.appName.isBlank() -> "订阅名称不能为空"
        subscription.amountMinor <= 0 -> "金额必须大于 0"
        currencyOrNull(subscription.currency) == null -> "币种必须为有效的 ISO 4217 代码"
        subscription.startDate.isAfter(subscription.nextBillingDate) -> "开始日期不能晚于下次扣费日期"
        subscription.cancelDate != null && subscription.cancelDate.isBefore(subscription.startDate) -> "取消日期不能早于开始日期"
        else -> null
    }

    /** Converts a positive decimal amount to minor units without rounding or floating point. */
    fun parseAmountMinor(amount: String, currency: String = CNY): Long? {
        val fractionDigits = currencyOrNull(currency)?.defaultFractionDigits ?: return null
        if (fractionDigits < 0) return null
        return runCatching {
            BigDecimal(amount.trim()).movePointRight(fractionDigits).longValueExact()
        }.getOrNull()?.takeIf { it > 0 }
    }

    fun effectiveNextBillingDate(subscription: Subscription, today: LocalDate): LocalDate? {
        if (!subscription.isActive) return null
        val anchor = subscription.nextBillingDate
        val cycleIndex = anchor.cycleIndexOnOrAfter(today, subscription.billingCycle).coerceAtLeast(0)
        val date = anchor.occurrence(subscription.billingCycle, cycleIndex)
        return date.takeUnless { subscription.cancelDate?.let(date::isAfter) == true }
    }

    fun forecastOccurrences(
        subscription: Subscription,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
    ): List<LocalDate> {
        if (!subscription.isActive || rangeEnd.isBefore(rangeStart)) return emptyList()
        val firstDate = maxOf(rangeStart, subscription.startDate)
        val lastDate = subscription.cancelDate?.let { minOf(rangeEnd, it) } ?: rangeEnd
        if (lastDate.isBefore(firstDate)) return emptyList()
        val anchor = subscription.nextBillingDate
        var cycleIndex = anchor.cycleIndexOnOrAfter(firstDate, subscription.billingCycle)
        var date = anchor.occurrence(subscription.billingCycle, cycleIndex)
        return buildList {
            while (!date.isAfter(lastDate)) {
                add(date)
                cycleIndex++
                date = anchor.occurrence(subscription.billingCycle, cycleIndex)
            }
        }
    }

    fun calculateStats(
        subscriptions: List<Subscription>,
        payments: List<SubscriptionPayment>,
        today: LocalDate,
    ): SubscriptionStats {
        val months = (5L downTo 0L).map { YearMonth.from(today).minusMonths(it) }
        val cnySubscriptions = subscriptions.filter { it.currency == CNY }
        val cnyPayments = payments.filter { it.currency == CNY }
        val monthStats = months.map { month ->
            val forecast = cnySubscriptions.sumExact { subscription ->
                Math.multiplyExact(forecastOccurrences(subscription, month.atDay(1), month.atEndOfMonth()).size.toLong(), subscription.amountMinor)
            }
            val actual = cnyPayments.filter { YearMonth.from(it.paidAt) == month }.sumExact(SubscriptionPayment::amountMinor)
            SubscriptionMonthStats(month, forecast, actual)
        }
        val currentMonth = monthStats.last()
        val monthStart = YearMonth.from(today).atDay(1)
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        val categoryStats = cnySubscriptions.groupBy { it.category?.ifBlank { null } ?: "未分类" }
            .map { (category, items) ->
                SubscriptionCategoryStats(category, items.sumExact { subscription ->
                    Math.multiplyExact(forecastOccurrences(subscription, monthStart, monthEnd).size.toLong(), subscription.amountMinor)
                })
            }.filter { it.amountMinor > 0 }.sortedByDescending(SubscriptionCategoryStats::amountMinor)
        val billingCycleStats = cnySubscriptions.groupBy(Subscription::billingCycle)
            .map { (cycle, items) ->
                BillingCycleStats(cycle, items.sumExact { subscription ->
                    Math.multiplyExact(forecastOccurrences(subscription, monthStart, monthEnd).size.toLong(), subscription.amountMinor)
                })
            }.filter { it.amountMinor > 0 }.sortedBy { it.billingCycle.ordinal }
        return SubscriptionStats(currentMonth, monthStats, categoryStats, billingCycleStats)
    }

    fun exportCsv(subscriptions: List<Subscription>, payments: List<SubscriptionPayment>): String = buildString {
        appendLine("record_type,subscription_id,app_name,amount_minor,currency,billing_cycle,next_billing_date,start_date,category,note,is_active,cancel_date,paid_at")
        subscriptions.forEach { subscription ->
            appendLine(listOf(
                "subscription", subscription.id, subscription.appName, subscription.amountMinor, subscription.currency,
                subscription.billingCycle, subscription.nextBillingDate, subscription.startDate, subscription.category,
                subscription.note, subscription.isActive, subscription.cancelDate, null,
            ).joinToString(",") { value -> csv(value?.toString().orEmpty()) })
        }
        payments.forEach { payment ->
            appendLine(listOf(
                "payment", payment.subscriptionId, "", payment.amountMinor, payment.currency, "", "", "", "",
                payment.note, "", "", payment.paidAt,
            ).joinToString(",") { value -> csv(value?.toString().orEmpty()) })
        }
    }

    private fun currencyOrNull(code: String): Currency? = try {
        Currency.getInstance(code)
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <T> Iterable<T>.sumExact(amount: (T) -> Long): Long =
        fold(0L) { total, item -> Math.addExact(total, amount(item)) }

    private fun LocalDate.cycleIndexOnOrAfter(target: LocalDate, cycle: BillingCycle): Long {
        // Use calendar buckets rather than clamped dates to seek directly, including before the anchor.
        val approximateIndex = when (cycle) {
            BillingCycle.WEEKLY -> Math.floorDiv(ChronoUnit.DAYS.between(this, target), 7L)
            BillingCycle.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(this), YearMonth.from(target))
            BillingCycle.QUARTERLY -> Math.floorDiv(
                ChronoUnit.MONTHS.between(YearMonth.from(this), YearMonth.from(target)), 3L,
            )
            BillingCycle.YEARLY -> target.year.toLong() - year.toLong()
        }
        return if (occurrence(cycle, approximateIndex).isBefore(target)) approximateIndex + 1 else approximateIndex
    }

    private fun LocalDate.occurrence(cycle: BillingCycle, cycleIndex: Long): LocalDate = when (cycle) {
        BillingCycle.WEEKLY -> plusWeeks(cycleIndex)
        BillingCycle.MONTHLY -> plusMonths(cycleIndex)
        BillingCycle.QUARTERLY -> plusMonths(cycleIndex * 3)
        BillingCycle.YEARLY -> plusYears(cycleIndex)
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}
