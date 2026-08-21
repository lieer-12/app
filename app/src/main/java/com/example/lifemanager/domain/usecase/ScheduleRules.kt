package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.model.ScheduleOccurrence
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object ScheduleRules {
    fun validate(schedule: Schedule): String? = when {
        schedule.title.isBlank() -> "标题不能为空"
        schedule.repeatInterval < 1 -> "重复间隔至少为 1"
        schedule.reminderMinutes != null && schedule.reminderMinutes < 0 -> "提醒时间不能为负数"
        schedule.isAllDay && (schedule.allDayStartDate == null || schedule.allDayEndDate == null) -> "请选择全天日程的日期"
        schedule.isAllDay && schedule.allDayEndDate!!.isBefore(schedule.allDayStartDate) -> "结束日期不能早于开始日期"
        !schedule.isAllDay && (schedule.startAt == null || schedule.endAt == null) -> "请选择开始和结束时间"
        !schedule.isAllDay && !schedule.endAt!!.isAfter(schedule.startAt) -> "结束时间必须晚于开始时间"
        else -> null
    }

    fun occurrencesInRange(
        schedule: Schedule,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        zone: ZoneId = ZoneId.of(schedule.timeZone),
        exceptions: List<ScheduleException> = emptyList(),
    ): List<ScheduleOccurrence> {
        if (validate(schedule) != null || rangeEnd.isBefore(rangeStart)) return emptyList()
        val baseDate = startDate(schedule, zone)
        val cancelledDates = exceptions.filter { it.scheduleId == schedule.id && it.isCancelled }
            .map(ScheduleException::occurrenceDate)
            .toSet()
        val result = mutableListOf<ScheduleOccurrence>()
        var cursor = maxOf(baseDate, rangeStart.minusDays(maxDurationDays(schedule, zone)))
        val lastDate = minOf(rangeEnd, schedule.repeatEndDate ?: rangeEnd)
        while (!cursor.isAfter(lastDate)) {
            if (cursor !in cancelledDates && occursOn(schedule, baseDate, cursor)) {
                occurrenceFor(schedule, cursor, zone)?.takeIf { it.intersects(rangeStart, rangeEnd, zone) }?.let(result::add)
            }
            cursor = cursor.plusDays(1)
        }
        return result.sortedWith(compareBy({ it.startAt ?: it.allDayStartDate }, { it.title }))
    }

    fun conflictsFor(
        candidate: Schedule,
        existing: List<Schedule>,
        zone: ZoneId = ZoneId.of(candidate.timeZone),
    ): List<Schedule> {
        if (validate(candidate) != null) return emptyList()
        val candidateStart = startDate(candidate, zone)
        val candidateEnd = endDate(candidate, candidateStart, zone)
        val candidateOccurrences = occurrencesInRange(candidate, candidateStart, candidateEnd, zone)
        return existing.filter { it.id != candidate.id && validate(it) == null }.filter { other ->
            val otherZone = ZoneId.of(other.timeZone)
            val occurrences = occurrencesInRange(other, candidateStart, candidateEnd, otherZone)
            candidateOccurrences.any { first -> occurrences.any { second -> first.overlaps(second, zone) } }
        }
    }

    fun nextReminderOccurrence(
        schedule: Schedule,
        now: Instant,
        exceptions: List<ScheduleException> = emptyList(),
    ): ScheduleOccurrence? {
        val minutes = schedule.reminderMinutes ?: return null
        val zone = ZoneId.of(schedule.timeZone)
        val start = now.atZone(zone).toLocalDate()
        return occurrencesInRange(schedule, start, start.plusYears(2), zone, exceptions)
            .firstOrNull { occurrence -> occurrence.startAt?.minusSeconds(minutes * 60L)?.isAfter(now) == true }
    }

    private fun occursOn(schedule: Schedule, base: LocalDate, date: LocalDate): Boolean {
        if (date.isBefore(base)) return false
        return when (schedule.repeatRule) {
            ScheduleRepeatRule.NONE -> date == base
            ScheduleRepeatRule.DAILY -> ChronoUnit.DAYS.between(base, date) % schedule.repeatInterval == 0L
            ScheduleRepeatRule.WEEKLY -> weeklyMatch(schedule, base, date)
            ScheduleRepeatRule.MONTHLY -> monthlyMatch(base, date, schedule.repeatInterval)
            ScheduleRepeatRule.YEARLY -> yearlyMatch(base, date, schedule.repeatInterval)
            ScheduleRepeatRule.CUSTOM -> customMatch(schedule, base, date)
        }
    }

    private fun weeklyMatch(schedule: Schedule, base: LocalDate, date: LocalDate): Boolean {
        val weeks = ChronoUnit.WEEKS.between(base, date)
        val days = schedule.repeatDaysOfWeek.ifEmpty { setOf(base.dayOfWeek) }
        return weeks % schedule.repeatInterval == 0L && date.dayOfWeek in days
    }

    private fun customMatch(schedule: Schedule, base: LocalDate, date: LocalDate): Boolean =
        if (schedule.repeatDaysOfWeek.isEmpty()) {
            ChronoUnit.DAYS.between(base, date) % schedule.repeatInterval == 0L
        } else {
            ChronoUnit.WEEKS.between(base, date) % schedule.repeatInterval == 0L && date.dayOfWeek in schedule.repeatDaysOfWeek
        }

    private fun monthlyMatch(base: LocalDate, date: LocalDate, interval: Int): Boolean {
        val months = Period.between(base.withDayOfMonth(1), date.withDayOfMonth(1)).toTotalMonths()
        return months >= 0 && months % interval == 0L && date.dayOfMonth == minOf(base.dayOfMonth, date.lengthOfMonth())
    }

    private fun yearlyMatch(base: LocalDate, date: LocalDate, interval: Int): Boolean =
        date.month == base.month && date.dayOfMonth == minOf(base.dayOfMonth, date.lengthOfMonth()) &&
            (date.year - base.year) >= 0 && (date.year - base.year) % interval == 0

    private fun occurrenceFor(schedule: Schedule, date: LocalDate, zone: ZoneId): ScheduleOccurrence? =
        if (schedule.isAllDay) {
            val originalStart = schedule.allDayStartDate ?: return null
            val originalEnd = schedule.allDayEndDate ?: return null
            val span = ChronoUnit.DAYS.between(originalStart, originalEnd)
            ScheduleOccurrence(schedule.id, schedule.title, isAllDay = true, allDayStartDate = date, allDayEndDate = date.plusDays(span), color = schedule.color, source = schedule)
        } else {
            val originalStart = schedule.startAt ?: return null
            val originalEnd = schedule.endAt ?: return null
            val localTime = originalStart.atZone(zone).toLocalTime()
            val start = date.atTime(localTime).atZone(zone).toInstant()
            ScheduleOccurrence(schedule.id, schedule.title, start, start.plusMillis(ChronoUnit.MILLIS.between(originalStart, originalEnd)), false, color = schedule.color, source = schedule)
        }

    private fun startDate(schedule: Schedule, zone: ZoneId): LocalDate =
        if (schedule.isAllDay) schedule.allDayStartDate!! else schedule.startAt!!.atZone(zone).toLocalDate()

    private fun endDate(schedule: Schedule, start: LocalDate, zone: ZoneId): LocalDate =
        if (schedule.isAllDay) schedule.allDayEndDate!! else schedule.endAt!!.atZone(zone).toLocalDate()

    private fun maxDurationDays(schedule: Schedule, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(startDate(schedule, zone), endDate(schedule, startDate(schedule, zone), zone)).coerceAtLeast(0)

    private fun ScheduleOccurrence.intersects(rangeStart: LocalDate, rangeEnd: LocalDate, zone: ZoneId): Boolean {
        val start = allDayStartDate ?: startAt!!.atZone(zone).toLocalDate()
        val end = allDayEndDate ?: endAt!!.minusMillis(1).atZone(zone).toLocalDate()
        return !start.isAfter(rangeEnd) && !end.isBefore(rangeStart)
    }

    private fun ScheduleOccurrence.overlaps(other: ScheduleOccurrence, zone: ZoneId): Boolean {
        if (isAllDay || other.isAllDay) return intersects(other.startDate(zone), other.endDate(zone), zone)
        return startAt!! < other.endAt && other.startAt!! < endAt
    }

    private fun ScheduleOccurrence.startDate(zone: ZoneId): LocalDate = allDayStartDate ?: startAt!!.atZone(zone).toLocalDate()
    private fun ScheduleOccurrence.endDate(zone: ZoneId): LocalDate = allDayEndDate ?: endAt!!.minusMillis(1).atZone(zone).toLocalDate()
}
