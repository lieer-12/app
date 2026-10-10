package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlanStatisticsTest {
    private val now = Instant.parse("2026-10-10T16:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 10)

    @Test fun `empty database has zero counts and finite zero rate`() {
        assertEquals(PlanStatistics(0, 0, 0, 0, 0, 0.0), PlanStatisticsRules.calculate(emptyList(), now, zone))
    }

    @Test fun `historical blank title remains countable without weakening write validation`() {
        val historical = plan().copy(title = " \n", isCompleted = true, completedAt = now)
        val result = runCatching { PlanStatisticsRules.calculate(listOf(historical), now, zone) }
        assertTrue(result.isSuccess)
        assertEquals(PlanStatistics(1, 1, 0, 1, 0, 1.0), result.getOrThrow())
        assertEquals(PlanValidationError.TITLE_REQUIRED, PlanRules.validate(historical))
    }

    @Test fun `historical fixed offset zone counts date expiry but is rejected for new writes`() {
        val historical = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueDate = date, timeZone = "+08:00")
        assertTrue(PlanStatisticsRules.isOverdue(historical, now))
        assertEquals(PlanStatistics(1, 0, 1, 0, 1, 0.0), PlanStatisticsRules.calculate(listOf(historical), now, zone))
        assertEquals(PlanValidationError.TIME_ZONE_INVALID, PlanRules.validate(historical))
    }

    @Test fun `invalid historical zone cannot bypass read validation in undated or instant modes`() {
        val invalidPlans = listOf(
            plan().copy(timeZone = "invalid/zone", isCompleted = true, completedAt = now),
            plan().copy(timeZone = "invalid/zone", timeMode = PlanTimeMode.DEADLINE, dueAt = now.minusSeconds(1)),
        )
        invalidPlans.forEach { invalid ->
            assertEquals(PlanValidationError.TIME_ZONE_INVALID, PlanRules.validateForRead(invalid))
            assertFalse(PlanStatisticsRules.isOverdue(invalid, now))
            assertFailsWith<IllegalArgumentException> { PlanStatisticsRules.calculate(listOf(invalid), now, zone) }
        }
    }

    @Test fun `whole database rate includes undated dated and completed series once`() {
        val values = listOf(plan(1), plan(2).copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now), recurring(3).copy(isCompleted = true, completedAt = now))
        assertEquals(PlanStatistics(3, 1, 2, 1, 0, 1.0 / 3.0), PlanStatisticsRules.calculate(values, now, zone))
    }

    @Test fun `today completion counts dayless goals by completion instant in display zone`() {
        val values = listOf(
            plan(1).copy(isCompleted = true, completedAt = now),
            plan(2).copy(isCompleted = true, completedAt = now.minusMillis(1)),
            plan(3).copy(isCompleted = true, completedAt = now.plusSeconds(86400)),
            plan(4).copy(isCompleted = false, completedAt = now),
            plan(5).copy(isCompleted = true, completedAt = null),
        )
        val stats = PlanStatisticsRules.calculate(values, now, zone)
        assertEquals(4, stats.completedCount)
        assertEquals(1, stats.todayCompletedCount)
        assertEquals(0.8, stats.completionRate)
    }

    @Test fun `display zone changes completion day without changing overall totals`() {
        val values = listOf(plan(1).copy(isCompleted = true, completedAt = now.minusSeconds(3600)))
        assertEquals(0, PlanStatisticsRules.calculate(values, now, zone).todayCompletedCount)
        assertEquals(1, PlanStatisticsRules.calculate(values, now, ZoneId.of("UTC")).todayCompletedCount)
    }

    @Test fun `instant deadline is overdue only strictly after cutoff`() {
        val value = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now)
        assertFalse(PlanStatisticsRules.isOverdue(value, now))
        assertTrue(PlanStatisticsRules.isOverdue(value, now.plusMillis(1)))
    }

    @Test fun `date only deadline stays valid through local day and expires at midnight`() {
        val value = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueDate = date)
        assertFalse(PlanStatisticsRules.isOverdue(value, now.minusMillis(1)))
        assertTrue(PlanStatisticsRules.isOverdue(value, now))
    }

    @Test fun `date overdue uses plan zone not device or display zone`() {
        val value = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueDate = date, timeZone = "America/Los_Angeles")
        assertFalse(PlanStatisticsRules.isOverdue(value, now))
        assertTrue(PlanStatisticsRules.isOverdue(value, Instant.parse("2026-10-11T07:00:00Z")))
    }

    @Test fun `date deadline expiry follows DST short day instead of fixed 24 hours`() {
        val value = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueDate = LocalDate.of(2026, 3, 8), timeZone = "America/New_York")
        assertFalse(PlanStatisticsRules.isOverdue(value, Instant.parse("2026-03-09T03:59:59Z")))
        assertTrue(PlanStatisticsRules.isOverdue(value, Instant.parse("2026-03-09T04:00:00Z")))
    }

    @Test fun `timed interval becomes overdue after end not after start`() {
        val value = plan().copy(timeMode = PlanTimeMode.TIMED, startAt = now.minusSeconds(3600), endAt = now)
        assertFalse(PlanStatisticsRules.isOverdue(value, now))
        assertTrue(PlanStatisticsRules.isOverdue(value, now.plusMillis(1)))
    }

    @Test fun `all day inclusive last date is not overdue until next local day`() {
        val value = plan().copy(timeMode = PlanTimeMode.ALL_DAY, allDayStartDate = date.minusDays(2), allDayEndDate = date)
        assertFalse(PlanStatisticsRules.isOverdue(value, now.minusMillis(1)))
        assertTrue(PlanStatisticsRules.isOverdue(value, now))
    }

    @Test fun `completed and recurring series never become perpetually overdue`() {
        val completed = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now.minusSeconds(1), isCompleted = true)
        assertFalse(PlanStatisticsRules.isOverdue(completed, now))
        assertFalse(PlanStatisticsRules.isOverdue(recurring(), now))
        assertFalse(PlanStatisticsRules.isOverdue(plan().copy(timeMode = PlanTimeMode.ALL_DAY, allDayStartDate = date, allDayEndDate = date, repeatRule = ScheduleRepeatRule.DAILY), now))
    }

    @Test fun `retained recurring settings do not exempt a deadline from overdue`() {
        val value = recurring().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now.minusSeconds(1))
        assertTrue(PlanStatisticsRules.isOverdue(value, now))
    }

    @Test fun `none mode ignores stale dates for overdue`() {
        assertFalse(PlanStatisticsRules.isOverdue(plan().copy(dueAt = now.minusSeconds(1), dueDate = date), now))
    }

    @Test fun `invalid active date cannot manufacture a successful statistics result`() {
        val invalid = plan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now.minusSeconds(1), dueDate = date)
        assertFalse(PlanStatisticsRules.isOverdue(invalid, now))
        assertFailsWith<IllegalArgumentException> { PlanStatisticsRules.calculate(listOf(invalid), now, zone) }
    }

    @Test fun `statistics tally overdue while retaining global denominator`() {
        val values = listOf(plan(1).copy(timeMode = PlanTimeMode.DEADLINE, dueAt = now.minusSeconds(1)), plan(2), plan(3).copy(isCompleted = true, completedAt = now))
        assertEquals(PlanStatistics(3, 1, 2, 1, 1, 1.0 / 3.0), PlanStatisticsRules.calculate(values, now, zone))
    }

    private fun plan(id: Long = 1) = Plan(id = id, title = "目标", timeZone = "Asia/Shanghai", createdAt = now, updatedAt = now)
    private fun recurring(id: Long = 1) = plan(id).copy(timeMode = PlanTimeMode.TIMED, startAt = now.minusSeconds(7200), endAt = now.minusSeconds(3600), repeatRule = ScheduleRepeatRule.DAILY)
}
