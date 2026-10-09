package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.usecase.ScheduleRules
import java.time.Instant
import java.time.ZoneId

/** Qualifies the exact scheduled occurrence from current Room data, including exception dates. */
internal object ScheduleReminderEligibility {
    fun matches(schedule: Schedule, exceptions: List<ScheduleException>, startMillis: Long, now: Instant): Boolean {
        val minutes = schedule.reminderMinutes ?: return false
        if (schedule.isAllDay || ScheduleRules.validate(schedule) != null) return false
        val start = Instant.ofEpochMilli(startMillis)
        if (now.isBefore(start.minusSeconds(minutes * 60L))) return false
        val zone = ZoneId.of(schedule.timeZone)
        val date = start.atZone(zone).toLocalDate()
        return ScheduleRules.occurrencesInRange(schedule, date, date, zone, exceptions).any {
            it.startAt?.toEpochMilli() == startMillis && it.endAt?.isAfter(now) == true
        }
    }
}
