package com.example.lifemanager.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class BillingCycle { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

data class Subscription(
    val id: Long = 0,
    val appName: String,
    val amountMinor: Long,
    val currency: String = "CNY",
    val billingCycle: BillingCycle,
    val nextBillingDate: LocalDate,
    val startDate: LocalDate,
    val category: String? = null,
    val note: String? = null,
    val isActive: Boolean = true,
    val cancelDate: LocalDate? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SubscriptionPayment(
    val id: Long = 0,
    val subscriptionId: Long,
    val amountMinor: Long,
    val currency: String,
    val paidAt: LocalDate,
    val note: String? = null,
)

data class SubscriptionReminder(
    val subscriptionId: Long,
    val daysBefore: Int,
)

data class SubscriptionMonthStats(
    val month: YearMonth,
    val forecastMinor: Long,
    val actualMinor: Long,
)

data class SubscriptionCategoryStats(
    val category: String,
    val amountMinor: Long,
)

data class BillingCycleStats(
    val billingCycle: BillingCycle,
    val amountMinor: Long,
)

data class SubscriptionStats(
    val currentMonth: SubscriptionMonthStats,
    val months: List<SubscriptionMonthStats>,
    val categoryStats: List<SubscriptionCategoryStats>,
    val billingCycleStats: List<BillingCycleStats>,
)
