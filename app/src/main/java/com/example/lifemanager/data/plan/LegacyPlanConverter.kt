package com.example.lifemanager.data.plan

import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.DateTimeException
import java.time.ZoneId

object LegacyPlanConverter {
    /** Field conversion only: callers validate the full source/target and commit transactionally. */
    fun todo(source: TodoEntity, planId: Long, zone: ZoneId, preferences: LegacyReminderPreferences): LegacyPlanConversion {
        require(planId > 0) { "New plan id must be positive" }
        return LegacyPlanConversion(
            row = RawPlanRow(
                id = planId, title = source.title, description = source.description, priority = source.priority,
                color = 0xFF246956.toInt(), location = null, participants = null,
                timeMode = if (source.dueAt == null) PlanTimeMode.NONE else PlanTimeMode.DEADLINE,
                dueAt = source.dueAt, dueDate = null, startAt = null, endAt = null,
                allDayStartDate = null, allDayEndDate = null, timeZone = zone.id,
                isCompleted = source.isCompleted, completedAt = source.completedAt,
                repeatRule = ScheduleRepeatRule.NONE, repeatInterval = 1, repeatDaysOfWeek = null, repeatEndDate = null,
                reminderEnabled = preferences.todoEnabled, reminderMinutes = 15,
                parentId = null, sortOrder = source.sortOrder, createdAt = source.createdAt, updatedAt = source.updatedAt,
            ),
            reference = LegacyPlanReference(LegacyPlanSource.TODO, source.id, planId),
            parentSource = source.parentId?.let { LegacyPlanKey(LegacyPlanSource.TODO, it) },
        )
    }

    fun schedule(source: ScheduleEntity, planId: Long, preferences: LegacyReminderPreferences): LegacyPlanConversion {
        require(planId > 0) { "New plan id must be positive" }
        try {
            ZoneId.of(source.timeZone)
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("Invalid legacy schedule time zone", error)
        }
        return LegacyPlanConversion(
            row = RawPlanRow(
                id = planId, title = source.title, description = source.note, priority = TodoPriority.NONE,
                color = source.color, location = source.location, participants = source.participants,
                timeMode = if (source.isAllDay) PlanTimeMode.ALL_DAY else PlanTimeMode.TIMED,
                dueAt = null, dueDate = null, startAt = source.startAt, endAt = source.endAt,
                allDayStartDate = source.allDayStartDate, allDayEndDate = source.allDayEndDate,
                timeZone = source.timeZone, isCompleted = false, completedAt = null,
                repeatRule = source.repeatRule, repeatInterval = source.repeatInterval,
                repeatDaysOfWeek = source.repeatDaysOfWeek, repeatEndDate = source.repeatEndDate,
                reminderEnabled = preferences.scheduleEnabled && !source.isAllDay, reminderMinutes = source.reminderMinutes,
                parentId = null, sortOrder = 0, createdAt = source.createdAt, updatedAt = source.updatedAt,
            ),
            reference = LegacyPlanReference(LegacyPlanSource.SCHEDULE, source.id, planId),
            parentSource = null,
        )
    }
}
