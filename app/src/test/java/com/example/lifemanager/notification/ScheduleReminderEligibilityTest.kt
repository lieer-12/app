package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleReminderEligibilityTest {
    private val start = Instant.parse("2026-10-08T10:00:00Z")
    private val now = Instant.parse("2026-10-08T09:50:00Z")
    private val schedule = Schedule(id = 41, title = "current", startAt = start,
        endAt = start.plusSeconds(3600), reminderMinutes = 15, timeZone = "UTC")

    @Test fun eligibleCurrentOccurrenceAndDelayedInexactDeliveryQualify() {
        assertTrue(matches(schedule))
        assertTrue(ScheduleReminderEligibility.matches(schedule, emptyList(), start.toEpochMilli(), start.plusSeconds(60)))
    }
    @Test fun changedTimeRemovedReminderBlankTitleAndAllDayDoNotQualify() {
        assertFalse(matches(schedule.copy(startAt = start.plusSeconds(60))))
        assertFalse(matches(schedule.copy(reminderMinutes = null)))
        assertFalse(matches(schedule.copy(title = " ")))
        assertFalse(matches(schedule.copy(isAllDay = true)))
    }
    @Test fun earlyExpiredAndCancelledOccurrenceDoNotQualify() {
        assertFalse(ScheduleReminderEligibility.matches(schedule, emptyList(), start.toEpochMilli(), start.minusSeconds(901)))
        assertFalse(ScheduleReminderEligibility.matches(schedule, emptyList(), start.toEpochMilli(), start.plusSeconds(3600)))
        assertFalse(ScheduleReminderEligibility.matches(schedule,
            listOf(ScheduleException(41, LocalDate.of(2026, 10, 8), true)), start.toEpochMilli(), now))
    }
    @Test fun repeatRuleAndEndDateAreReadFromCurrentSchedule() {
        val original = schedule.copy(startAt = start.minusSeconds(86400), endAt = start.minusSeconds(82800))
        assertFalse(matches(original))
        assertTrue(matches(original.copy(repeatRule = ScheduleRepeatRule.DAILY)))
        assertFalse(matches(original.copy(repeatRule = ScheduleRepeatRule.DAILY, repeatEndDate = LocalDate.of(2026, 10, 7))))
    }
    private fun matches(current: Schedule) = ScheduleReminderEligibility.matches(current, emptyList(), start.toEpochMilli(), now)
}
