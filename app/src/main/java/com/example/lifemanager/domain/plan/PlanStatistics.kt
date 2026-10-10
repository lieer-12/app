package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.ZoneId

data class PlanStatistics(
    val totalCount: Int,
    val completedCount: Int,
    val pendingCount: Int,
    val todayCompletedCount: Int,
    val overdueCount: Int,
    val completionRate: Double,
)

object PlanStatisticsRules {
    /** Supply database rows, not filtered list items or expanded recurring occurrences. */
    fun calculate(allPlans: List<Plan>, now: Instant, displayZone: ZoneId): PlanStatistics {
        val today = now.atZone(displayZone).toLocalDate()
        var completed = 0
        var todayCompleted = 0
        var overdue = 0
        for (plan in allPlans) {
            val error = PlanRules.validateForRead(plan)
            require(error == null) { "Invalid plan ${plan.id} in statistics: $error" }
            if (plan.isCompleted) {
                completed++
                if (plan.completedAt?.atZone(displayZone)?.toLocalDate() == today) todayCompleted++
            }
            if (isOverdue(plan, now)) overdue++
        }
        val total = allPlans.size
        return PlanStatistics(
            totalCount = total,
            completedCount = completed,
            pendingCount = total - completed,
            todayCompletedCount = todayCompleted,
            overdueCount = overdue,
            completionRate = if (total == 0) 0.0 else completed.toDouble() / total,
        )
    }

    fun isOverdue(plan: Plan, now: Instant): Boolean {
        if (PlanRules.validateForRead(plan) != null || plan.isCompleted || plan.isRepeating) return false
        return when (plan.timeMode) {
            PlanTimeMode.NONE -> false
            PlanTimeMode.DEADLINE -> plan.dueAt?.let(now::isAfter)
                ?: now.atZone(ZoneId.of(plan.timeZone)).toLocalDate().isAfter(plan.dueDate)
            PlanTimeMode.TIMED -> now.isAfter(plan.endAt)
            PlanTimeMode.ALL_DAY -> now.atZone(ZoneId.of(plan.timeZone)).toLocalDate().isAfter(plan.allDayEndDate)
        }
    }
}
