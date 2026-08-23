package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.BillingCycleStats
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.SubscriptionCategoryStats
import com.example.lifemanager.domain.model.SubscriptionMonthStats
import com.example.lifemanager.domain.model.SubscriptionPayment
import com.example.lifemanager.domain.model.SubscriptionStats
import java.time.LocalDate
import java.time.YearMonth

object SubscriptionRules {
    private const val CNY = "CNY"

    fun validate(subscription: Subscription): String? = when {
        subscription.appName.isBlank() -> "订阅名称不能为空"
        subscription.amountMinor <= 0 -> "金额必须大于 0"
        subscription.startDate.isAfter(subscription.nextBillingDate) -> "开始日期不能晚于下次扣费日期"
        subscription.cancelDate != null && subscription.cancelDate.isBefore(subscription.startDate) -> "取消日期不能早于开始日期"
        else -> null
    }

    fun effectiveNextBillingDate(subscription: Subscription, today: LocalDate): LocalDate? {
        if (!subscription.isActive) return null
        var date = subscription.nextBillingDate
        while (date.isBefore(today)) date = date.advance(subscription.billingCycle)
        return date.takeUnless { subscription.cancelDate?.let(date::isAfter) == true }
    }

    fun forecastOccurrences(
        subscription: Subscription,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
    ): List<LocalDate> {
        if (!subscription.isActive || rangeEnd.isBefore(rangeStart)) return emptyList()
        var date = subscription.nextBillingDate
        while (date.previous(subscription.billingCycle).let { !it.isBefore(subscription.startDate) && !it.isBefore(rangeStart) }) {
            date = date.previous(subscription.billingCycle)
        }
        return buildList {
            while (!date.isAfter(rangeEnd)) {
                if (!date.isBefore(rangeStart) && (subscription.cancelDate == null || !date.isAfter(subscription.cancelDate))) add(date)
                date = date.advance(subscription.billingCycle)
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
            val forecast = cnySubscriptions.sumOf { subscription ->
                forecastOccurrences(subscription, month.atDay(1), month.atEndOfMonth()).size * subscription.amountMinor
            }
            val actual = cnyPayments.filter { YearMonth.from(it.paidAt) == month }.sumOf(SubscriptionPayment::amountMinor)
            SubscriptionMonthStats(month, forecast, actual)
        }
        val currentMonth = monthStats.last()
        val monthStart = YearMonth.from(today).atDay(1)
        val monthEnd = YearMonth.from(today).atEndOfMonth()
        val categoryStats = cnySubscriptions.groupBy { it.category?.ifBlank { null } ?: "未分类" }
            .map { (category, items) ->
                SubscriptionCategoryStats(category, items.sumOf { subscription ->
                    forecastOccurrences(subscription, monthStart, monthEnd).size * subscription.amountMinor
                })
            }.filter { it.amountMinor > 0 }.sortedByDescending(SubscriptionCategoryStats::amountMinor)
        val billingCycleStats = cnySubscriptions.groupBy(Subscription::billingCycle)
            .map { (cycle, items) ->
                BillingCycleStats(cycle, items.sumOf { subscription ->
                    forecastOccurrences(subscription, monthStart, monthEnd).size * subscription.amountMinor
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

    private fun LocalDate.advance(cycle: BillingCycle): LocalDate = when (cycle) {
        BillingCycle.WEEKLY -> plusWeeks(1)
        BillingCycle.MONTHLY -> plusMonths(1)
        BillingCycle.QUARTERLY -> plusMonths(3)
        BillingCycle.YEARLY -> plusYears(1)
    }

    private fun LocalDate.previous(cycle: BillingCycle): LocalDate = when (cycle) {
        BillingCycle.WEEKLY -> minusWeeks(1)
        BillingCycle.MONTHLY -> minusMonths(1)
        BillingCycle.QUARTERLY -> minusMonths(3)
        BillingCycle.YEARLY -> minusYears(1)
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}
