package com.example.lifemanager.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

enum class ScheduleRepeatRule {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM,
}

data class Schedule(
    val id: Long = 0,
    val title: String,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val isAllDay: Boolean = false,
    val allDayStartDate: LocalDate? = null,
    val allDayEndDate: LocalDate? = null,
    val location: String? = null,
    val participants: String? = null,
    val note: String? = null,
    val color: Int = 0xFF00695C.toInt(),
    val reminderMinutes: Int? = null,
    val repeatRule: ScheduleRepeatRule = ScheduleRepeatRule.NONE,
    val repeatInterval: Int = 1,
    val repeatDaysOfWeek: Set<DayOfWeek> = emptySet(),
    val repeatEndDate: LocalDate? = null,
    val timeZone: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class ScheduleException(
    val scheduleId: Long,
    val occurrenceDate: LocalDate,
    val isCancelled: Boolean,
)

data class ScheduleOccurrence(
    val scheduleId: Long,
    val title: String,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val isAllDay: Boolean,
    val allDayStartDate: LocalDate? = null,
    val allDayEndDate: LocalDate? = null,
    val color: Int,
    val source: Schedule,
)
