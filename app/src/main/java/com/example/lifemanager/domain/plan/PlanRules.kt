package com.example.lifemanager.domain.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class PlanValidationError {
    TITLE_REQUIRED, TIME_ZONE_INVALID, REPEAT_INTERVAL_INVALID, REMINDER_MINUTES_INVALID,
    DEADLINE_REQUIRED, DEADLINE_AMBIGUOUS, INTERVAL_REQUIRED, INTERVAL_ORDER,
    ALL_DAY_REQUIRED, ALL_DAY_ORDER,
}

object PlanRules {
    private val timeZoneIds = ZoneId.getAvailableZoneIds()

    /** Validates active time fields without clearing retained, inactive configuration. */
    fun validate(plan: Plan): PlanValidationError? {
        when {
            plan.title.isBlank() -> return PlanValidationError.TITLE_REQUIRED
            plan.timeZone !in timeZoneIds -> return PlanValidationError.TIME_ZONE_INVALID
            plan.repeatInterval < 1 -> return PlanValidationError.REPEAT_INTERVAL_INVALID
            plan.reminderMinutes != null && plan.reminderMinutes < 0 -> return PlanValidationError.REMINDER_MINUTES_INVALID
        }
        return when (plan.timeMode) {
            PlanTimeMode.NONE -> null
            PlanTimeMode.DEADLINE -> when {
                plan.dueAt == null && plan.dueDate == null -> PlanValidationError.DEADLINE_REQUIRED
                plan.dueAt != null && plan.dueDate != null -> PlanValidationError.DEADLINE_AMBIGUOUS
                else -> null
            }
            PlanTimeMode.TIMED -> when {
                plan.startAt == null || plan.endAt == null -> PlanValidationError.INTERVAL_REQUIRED
                !plan.endAt.isAfter(plan.startAt) -> PlanValidationError.INTERVAL_ORDER
                else -> null
            }
            PlanTimeMode.ALL_DAY -> when {
                plan.allDayStartDate == null || plan.allDayEndDate == null -> PlanValidationError.ALL_DAY_REQUIRED
                plan.allDayEndDate.isBefore(plan.allDayStartDate) -> PlanValidationError.ALL_DAY_ORDER
                else -> null
            }
        }
    }

    /** For repeating plans this completes the entire series, not an occurrence. */
    fun complete(plan: Plan, now: Instant): Plan =
        if (plan.isCompleted) plan else plan.copy(isCompleted = true, completedAt = now, updatedAt = now)

    fun undoCompletion(plan: Plan, now: Instant): Plan =
        if (!plan.isCompleted && plan.completedAt == null) plan
        else plan.copy(isCompleted = false, completedAt = null, updatedAt = now)

    /** UI must confirm deactivation of intervals/repeats before invoking this operation. */
    fun withoutDate(plan: Plan, now: Instant): Plan = plan.copy(timeMode = PlanTimeMode.NONE, updatedAt = now)

    fun withDeadline(plan: Plan, now: Instant, dueAt: Instant? = null, dueDate: LocalDate? = null): Plan {
        val updated = plan.copy(timeMode = PlanTimeMode.DEADLINE, dueAt = dueAt, dueDate = dueDate, updatedAt = now)
        val error = validate(updated)
        require(error == null) { "Invalid deadline conversion: $error" }
        return updated
    }
}
