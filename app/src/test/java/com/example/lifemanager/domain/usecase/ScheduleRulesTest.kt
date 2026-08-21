package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `blank title and invalid end time are rejected`() {
        assertEquals("标题不能为空", ScheduleRules.validate(timed(title = "  ")))
        assertEquals("结束时间必须晚于开始时间", ScheduleRules.validate(timed(end = Instant.parse("2026-08-22T01:00:00Z"))))
    }

    @Test
    fun `daily recurrence is expanded only in requested range and respects end date`() {
        val schedule = timed().copy(
            repeatRule = ScheduleRepeatRule.DAILY,
            repeatEndDate = LocalDate.of(2026, 8, 24),
        )

        val occurrences = ScheduleRules.occurrencesInRange(
            schedule,
            LocalDate.of(2026, 8, 21),
            LocalDate.of(2026, 8, 27),
            zone,
        )

        assertEquals(listOf(22, 23, 24), occurrences.map { it.startAt!!.atZone(zone).dayOfMonth })
    }

    @Test
    fun `only overlapping timed schedules are returned as conflicts`() {
        val candidate = timed(id = 1)
        val overlap = timed(id = 2, start = Instant.parse("2026-08-22T01:30:00Z"), end = Instant.parse("2026-08-22T02:30:00Z"))
        val separate = timed(id = 3, start = Instant.parse("2026-08-22T02:00:00Z"), end = Instant.parse("2026-08-22T03:00:00Z"))

        assertEquals(listOf(2L), ScheduleRules.conflictsFor(candidate, listOf(overlap, separate), zone).map(Schedule::id))
    }

    private fun timed(
        id: Long = 0,
        title: String = "会议",
        start: Instant = Instant.parse("2026-08-22T01:00:00Z"),
        end: Instant = Instant.parse("2026-08-22T02:00:00Z"),
    ) = Schedule(id = id, title = title, startAt = start, endAt = end, timeZone = zone.id)
}
