package com.example.lifemanager.domain.plan

import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanRulesTest {
    private val created = Instant.parse("2026-10-01T00:00:00Z")
    private val now = Instant.parse("2026-10-10T02:00:00Z")
    private val start = Instant.parse("2026-10-11T02:00:00Z")
    private val end = Instant.parse("2026-10-11T03:00:00Z")
    private val date = LocalDate.of(2026, 10, 11)

    @Test fun `only active mode classifies dated plans`() {
        val dayless = plan().copy(startAt = start, endAt = end, dueAt = end)
        assertFalse(dayless.isScheduled)
        for (mode in listOf(PlanTimeMode.DEADLINE, PlanTimeMode.TIMED, PlanTimeMode.ALL_DAY)) {
            assertTrue(dayless.copy(timeMode = mode).isScheduled)
        }
    }

    @Test fun `none accepts retained time and repeat configuration without activating it`() {
        val value = recurring().copy(timeMode = PlanTimeMode.NONE)
        assertNull(PlanRules.validate(value))
        assertFalse(value.isRepeating)
    }

    @Test fun `deadline needs one and only one cutoff`() {
        assertEquals(PlanValidationError.DEADLINE_REQUIRED, PlanRules.validate(plan().copy(timeMode = PlanTimeMode.DEADLINE)))
        assertEquals(PlanValidationError.DEADLINE_AMBIGUOUS, PlanRules.validate(plan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = end, dueDate = date)))
        assertNull(PlanRules.validate(plan().copy(timeMode = PlanTimeMode.DEADLINE, dueAt = end)))
        assertNull(PlanRules.validate(plan().copy(timeMode = PlanTimeMode.DEADLINE, dueDate = date)))
    }

    @Test fun `deadline ignores retained interval and repeat settings`() {
        val value = recurring().copy(timeMode = PlanTimeMode.DEADLINE, dueDate = date)
        assertNull(PlanRules.validate(value))
        assertFalse(value.isRepeating)
    }

    @Test fun `timed requires a strictly increasing complete interval`() {
        val base = plan().copy(timeMode = PlanTimeMode.TIMED)
        assertEquals(PlanValidationError.INTERVAL_REQUIRED, PlanRules.validate(base))
        assertEquals(PlanValidationError.INTERVAL_REQUIRED, PlanRules.validate(base.copy(startAt = start)))
        assertEquals(PlanValidationError.INTERVAL_REQUIRED, PlanRules.validate(base.copy(endAt = end)))
        assertEquals(PlanValidationError.INTERVAL_ORDER, PlanRules.validate(base.copy(startAt = start, endAt = start)))
        assertEquals(PlanValidationError.INTERVAL_ORDER, PlanRules.validate(base.copy(startAt = end, endAt = start)))
        assertNull(PlanRules.validate(base.copy(startAt = start, endAt = end)))
    }

    @Test fun `all day permits one inclusive date but not reversed or missing dates`() {
        val base = plan().copy(timeMode = PlanTimeMode.ALL_DAY)
        assertEquals(PlanValidationError.ALL_DAY_REQUIRED, PlanRules.validate(base))
        assertEquals(PlanValidationError.ALL_DAY_REQUIRED, PlanRules.validate(base.copy(allDayStartDate = date)))
        assertEquals(PlanValidationError.ALL_DAY_REQUIRED, PlanRules.validate(base.copy(allDayEndDate = date)))
        assertEquals(PlanValidationError.ALL_DAY_ORDER, PlanRules.validate(base.copy(allDayStartDate = date, allDayEndDate = date.minusDays(1))))
        assertNull(PlanRules.validate(base.copy(allDayStartDate = date, allDayEndDate = date)))
    }

    @Test fun `invalid shared fields are rejected even in inactive mode`() {
        assertEquals(PlanValidationError.TITLE_REQUIRED, PlanRules.validate(plan().copy(title = "  \n")))
        assertEquals(PlanValidationError.TIME_ZONE_INVALID, PlanRules.validate(plan().copy(timeZone = "not/a-zone")))
        assertEquals(PlanValidationError.TIME_ZONE_INVALID, PlanRules.validate(plan().copy(timeZone = "+08:00")))
        assertEquals(PlanValidationError.REPEAT_INTERVAL_INVALID, PlanRules.validate(plan().copy(repeatInterval = 0)))
        assertEquals(PlanValidationError.REMINDER_MINUTES_INVALID, PlanRules.validate(plan().copy(reminderMinutes = -1)))
        assertNull(PlanRules.validate(plan().copy(reminderMinutes = null)))
        assertNull(PlanRules.validate(plan().copy(reminderMinutes = 0)))
    }

    @Test fun `completing series preserves content and configuration`() {
        val original = recurring()
        assertEquals(original.copy(isCompleted = true, completedAt = now, updatedAt = now), PlanRules.complete(original, now))
    }

    @Test fun `repeated completion cannot move original completion timestamp`() {
        val completed = recurring().copy(isCompleted = true, completedAt = created)
        assertSame(completed, PlanRules.complete(completed, now))
    }

    @Test fun `completion preserves historical completed flag with absent timestamp`() {
        val historical = plan().copy(isCompleted = true, completedAt = null)
        assertSame(historical, PlanRules.complete(historical, now))
    }

    @Test fun `completing historical pending record replaces stale completion timestamp`() {
        val value = recurring().copy(isCompleted = false, completedAt = created)
        assertEquals(value.copy(isCompleted = true, completedAt = now, updatedAt = now), PlanRules.complete(value, now))
    }

    @Test fun `undo clears completion without resetting repeat anchor`() {
        val value = recurring().copy(isCompleted = true, completedAt = created)
        assertEquals(value.copy(isCompleted = false, completedAt = null, updatedAt = now), PlanRules.undoCompletion(value, now))
    }

    @Test fun `undo of pending normal state is idempotent`() {
        val value = plan()
        assertSame(value, PlanRules.undoCompletion(value, now))
    }

    @Test fun `undo of historical completed record without timestamp clears completed flag`() {
        val value = recurring().copy(isCompleted = true, completedAt = null)
        assertEquals(value.copy(isCompleted = false, completedAt = null, updatedAt = now), PlanRules.undoCompletion(value, now))
    }

    @Test fun `undo of inconsistent historical pending record clears completion timestamp`() {
        val value = plan().copy(completedAt = created)
        assertEquals(value.copy(completedAt = null, updatedAt = now), PlanRules.undoCompletion(value, now))
    }

    @Test fun `adding instant deadline preserves identity content and completion`() {
        val value = recurring().copy(timeMode = PlanTimeMode.NONE, isCompleted = true, completedAt = created)
        assertEquals(value.copy(timeMode = PlanTimeMode.DEADLINE, dueAt = end, dueDate = null, updatedAt = now), PlanRules.withDeadline(value, now, dueAt = end))
    }

    @Test fun `date only deadline clears old active cutoff but retains inactive interval`() {
        val value = recurring().copy(dueAt = start)
        assertEquals(value.copy(timeMode = PlanTimeMode.DEADLINE, dueAt = null, dueDate = date, updatedAt = now), PlanRules.withDeadline(value, now, dueDate = date))
    }

    @Test fun `deadline conversion rejects missing or ambiguous cutoffs`() {
        assertFailsWith<IllegalArgumentException> { PlanRules.withDeadline(plan(), now) }
        assertFailsWith<IllegalArgumentException> { PlanRules.withDeadline(plan(), now, dueAt = end, dueDate = date) }
    }

    @Test fun `remove date retains all time fields reminder and repeat preferences`() {
        val value = recurring().copy(dueAt = start, dueDate = date, allDayStartDate = date, allDayEndDate = date.plusDays(2))
        val removed = PlanRules.withoutDate(value, now)
        assertEquals(value.copy(timeMode = PlanTimeMode.NONE, updatedAt = now), removed)
        assertFalse(removed.isRepeating)
        assertFalse(removed.isScheduled)
    }

    private fun plan() = Plan(
        id = 42, title = "  长期目标  ", description = "\n不裁剪备注\n", timeZone = "Asia/Shanghai",
        priority = TodoPriority.HIGH, parentId = 100, sortOrder = 7, tagIds = setOf(1, 3),
        createdAt = created, updatedAt = created,
    )

    private fun recurring() = plan().copy(
        timeMode = PlanTimeMode.TIMED, startAt = start, endAt = end, repeatRule = ScheduleRepeatRule.CUSTOM,
        repeatInterval = 2, repeatDaysOfWeek = setOf(java.time.DayOfWeek.MONDAY), repeatEndDate = date.plusMonths(1),
        location = "  地点  ", participants = "伙伴", reminderEnabled = true, reminderMinutes = 30,
    )
}
