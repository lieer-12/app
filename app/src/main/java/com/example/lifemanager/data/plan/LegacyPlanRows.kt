package com.example.lifemanager.data.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode

/** Raw field contract shared by migration and validated legacy-backup conversion. */
data class RawPlanRow(
    val id: Long,
    val title: String,
    val description: String?,
    val priority: TodoPriority,
    val color: Int,
    val location: String?,
    val participants: String?,
    val timeMode: PlanTimeMode,
    val dueAt: Long?,
    val dueDate: Long?,
    val startAt: Long?,
    val endAt: Long?,
    val allDayStartDate: Long?,
    val allDayEndDate: Long?,
    val timeZone: String,
    val isCompleted: Boolean,
    val completedAt: Long?,
    val repeatRule: ScheduleRepeatRule,
    val repeatInterval: Int,
    val repeatDaysOfWeek: String?,
    val repeatEndDate: Long?,
    val reminderEnabled: Boolean,
    val reminderMinutes: Int?,
    val parentId: Long?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class LegacyPlanSource { TODO, SCHEDULE }
data class LegacyPlanKey(val source: LegacyPlanSource, val legacyId: Long)
data class LegacyPlanReference(val source: LegacyPlanSource, val legacyId: Long, val planId: Long) {
    val key: LegacyPlanKey get() = LegacyPlanKey(source, legacyId)
}
data class LegacyPlanConversion(val row: RawPlanRow, val reference: LegacyPlanReference, val parentSource: LegacyPlanKey?)
data class PlanTagLink(val planId: Long, val tagId: Long)
data class PlanExceptionRow(val planId: Long, val occurrenceDate: Long, val isCancelled: Boolean)

data class LegacyReminderPreferences(val todoEnabled: Boolean, val scheduleEnabled: Boolean) {
    val planEnabled: Boolean get() = todoEnabled || scheduleEnabled
}
