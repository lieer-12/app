package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

enum class PlanTimeMode { NONE, DEADLINE, TIMED, ALL_DAY }

/** Unified domain contract; raw legacy field preservation belongs to the data layer. */
data class Plan(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val priority: TodoPriority = TodoPriority.NONE,
    val color: Int = 0xFF246956.toInt(),
    val location: String? = null,
    val participants: String? = null,
    val timeMode: PlanTimeMode = PlanTimeMode.NONE,
    val dueAt: Instant? = null,
    val dueDate: LocalDate? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val allDayStartDate: LocalDate? = null,
    val allDayEndDate: LocalDate? = null,
    val timeZone: String,
    val isCompleted: Boolean = false,
    val completedAt: Instant? = null,
    val repeatRule: ScheduleRepeatRule = ScheduleRepeatRule.NONE,
    val repeatInterval: Int = 1,
    val repeatDaysOfWeek: Set<DayOfWeek> = emptySet(),
    val repeatEndDate: LocalDate? = null,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int? = 15,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val tagIds: Set<Long> = emptySet(),
    val tagNames: List<String> = emptyList(),
) {
    val isScheduled: Boolean get() = timeMode != PlanTimeMode.NONE
    val isRepeating: Boolean get() =
        (timeMode == PlanTimeMode.TIMED || timeMode == PlanTimeMode.ALL_DAY) && repeatRule != ScheduleRepeatRule.NONE
}
