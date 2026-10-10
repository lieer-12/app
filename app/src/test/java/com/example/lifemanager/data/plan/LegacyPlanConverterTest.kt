package com.example.lifemanager.data.plan

import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.plan.PlanTimeMode
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyPlanConverterTest {
    private val enabled = LegacyReminderPreferences(true, true)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun `todo copies every raw field and documented defaults without id arithmetic`() {
        val actual = converted { LegacyPlanConverter.todo(todo(), 1, zone, enabled) }
        assertEquals(RawPlanRow(
            id = 1, title = "  原文🌱  ", description = "\n备注\n", priority = TodoPriority.HIGH,
            color = 0xFF246956.toInt(), location = null, participants = null, timeMode = PlanTimeMode.DEADLINE,
            dueAt = 9007199254740993L, dueDate = null, startAt = null, endAt = null,
            allDayStartDate = null, allDayEndDate = null, timeZone = "Asia/Shanghai",
            isCompleted = true, completedAt = Long.MAX_VALUE, repeatRule = ScheduleRepeatRule.NONE,
            repeatInterval = 1, repeatDaysOfWeek = null, repeatEndDate = null, reminderEnabled = true,
            reminderMinutes = 15, parentId = null, sortOrder = -7, createdAt = Long.MIN_VALUE, updatedAt = Long.MAX_VALUE,
        ), actual.row)
        assertEquals(LegacyPlanReference(LegacyPlanSource.TODO, Long.MAX_VALUE, 1), actual.reference)
        assertEquals(LegacyPlanKey(LegacyPlanSource.TODO, 2), actual.parentSource)
    }

    @Test fun `undated todo keeps reminder preference without inventing a date`() {
        val actual = converted { LegacyPlanConverter.todo(todo().copy(dueAt = null, parentId = null), 2, zone, enabled) }
        assertEquals(PlanTimeMode.NONE, actual.row.timeMode)
        assertNull(actual.row.dueAt)
        assertNull(actual.row.dueDate)
        assertNull(actual.parentSource)
        assertTrue(actual.row.reminderEnabled)
    }

    @Test fun `schedule preserves every source field including inactive dates and raw repeat string`() {
        val actual = converted { LegacyPlanConverter.schedule(schedule(), 2, enabled) }
        assertEquals(RawPlanRow(
            id = 2, title = "  日程  ", description = "\n原备注\n", priority = TodoPriority.NONE,
            color = Int.MIN_VALUE, location = "  原地点  ", participants = "", timeMode = PlanTimeMode.TIMED,
            dueAt = null, dueDate = null, startAt = 1300, endAt = 1900, allDayStartDate = 10, allDayEndDate = 12,
            timeZone = "Europe/Paris", isCompleted = false, completedAt = null,
            repeatRule = ScheduleRepeatRule.CUSTOM, repeatInterval = 3, repeatDaysOfWeek = "2,1,2",
            repeatEndDate = 2000, reminderEnabled = true, reminderMinutes = 30, parentId = null,
            sortOrder = 0, createdAt = Long.MIN_VALUE, updatedAt = Long.MAX_VALUE,
        ), actual.row)
        assertEquals(LegacyPlanReference(LegacyPlanSource.SCHEDULE, Long.MAX_VALUE, 2), actual.reference)
        assertNull(actual.parentSource)
    }

    @Test fun `all day keeps inclusive epoch days and inactive instants but never auto enables reminders`() {
        val actual = converted { LegacyPlanConverter.schedule(schedule().copy(isAllDay = true), 3, enabled) }
        assertEquals(PlanTimeMode.ALL_DAY, actual.row.timeMode)
        assertEquals(10L, actual.row.allDayStartDate)
        assertEquals(12L, actual.row.allDayEndDate)
        assertEquals(1300L, actual.row.startAt)
        assertEquals(1900L, actual.row.endAt)
        assertEquals(false, actual.row.reminderEnabled)
        assertEquals(30, actual.row.reminderMinutes)
    }

    @Test fun `four legacy preference combinations retain per item qualification and OR global flag`() {
        val cases = listOf(
            Triple(LegacyReminderPreferences(false, false), false, false),
            Triple(LegacyReminderPreferences(false, true), false, true),
            Triple(LegacyReminderPreferences(true, false), true, false),
            Triple(LegacyReminderPreferences(true, true), true, true),
        )
        for ((prefs, todoExpected, scheduleExpected) in cases) {
            assertEquals(todoExpected, converted { LegacyPlanConverter.todo(todo(), 1, zone, prefs) }.row.reminderEnabled)
            assertEquals(scheduleExpected, converted { LegacyPlanConverter.schedule(schedule(), 2, prefs) }.row.reminderEnabled)
        }
        assertEquals(listOf(false, true, true, true), cases.map { it.first.planEnabled })
    }

    @Test fun `null reminder minutes remain null and do not synthesize a schedule reminder`() {
        val actual = converted { LegacyPlanConverter.schedule(schedule().copy(reminderMinutes = null), 1, enabled) }
        assertNull(actual.row.reminderMinutes)
        assertEquals(true, actual.row.reminderEnabled)
    }

    @Test fun `null and empty texts and repeat strings remain distinct`() {
        for (text in listOf(null, "")) {
            val value = schedule().copy(note = text, location = text, participants = text, repeatDaysOfWeek = text)
            val row = converted { LegacyPlanConverter.schedule(value, 1, enabled) }.row
            assertEquals(text, row.description)
            assertEquals(text, row.location)
            assertEquals(text, row.participants)
            assertEquals(text, row.repeatDaysOfWeek)
            assertEquals(text, converted { LegacyPlanConverter.todo(todo().copy(description = text), 2, zone, enabled) }.row.description)
        }
    }

    @Test fun `all historical todo completion combinations are preserved not repaired`() {
        for (completed in listOf(false, true)) for (timestamp in listOf(null, 7L)) {
            val row = converted { LegacyPlanConverter.todo(todo().copy(isCompleted = completed, completedAt = timestamp), 1, zone, enabled) }.row
            assertEquals(completed, row.isCompleted)
            assertEquals(timestamp, row.completedAt)
        }
    }

    @Test fun `legacy fixed zones and blank titles are not normalized`() {
        val scheduleRow = converted { LegacyPlanConverter.schedule(schedule().copy(title = " \n", timeZone = "+08:00"), 1, enabled) }.row
        assertEquals(" \n", scheduleRow.title)
        assertEquals("+08:00", scheduleRow.timeZone)
        val todoRow = converted { LegacyPlanConverter.todo(todo().copy(title = ""), 2, ZoneId.of("+05:30"), enabled) }.row
        assertEquals("", todoRow.title)
        assertEquals("+05:30", todoRow.timeZone)
    }

    @Test fun `legacy signed and zero identifiers keep exact source identity`() {
        for (id in listOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE)) {
            assertEquals(id, converted { LegacyPlanConverter.todo(todo().copy(id = id), 1, zone, enabled) }.reference.legacyId)
            assertEquals(id, converted { LegacyPlanConverter.schedule(schedule().copy(id = id), 2, enabled) }.reference.legacyId)
        }
    }

    @Test fun `new plan id must be positive while legacy identifiers may be any Long`() {
        for (id in listOf(Long.MIN_VALUE, -1L, 0L)) {
            assertFailsWith<IllegalArgumentException> { LegacyPlanConverter.todo(todo(), id, zone, enabled) }
            assertFailsWith<IllegalArgumentException> { LegacyPlanConverter.schedule(schedule(), id, enabled) }
        }
    }

    @Test fun `invalid schedule zone is rejected rather than replaced with device zone`() {
        assertFailsWith<IllegalArgumentException> { LegacyPlanConverter.schedule(schedule().copy(timeZone = "invalid/zone"), 1, enabled) }
    }

    private fun converted(block: () -> LegacyPlanConversion): LegacyPlanConversion {
        val result = runCatching(block)
        assertTrue(result.isSuccess, "Expected successful lossless conversion, got ${result.exceptionOrNull()}")
        return result.getOrThrow()
    }

    private fun todo() = TodoEntity(Long.MAX_VALUE, "  原文🌱  ", "\n备注\n", TodoPriority.HIGH,
        9007199254740993L, true, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, 2, -7)
    private fun schedule() = ScheduleEntity(Long.MAX_VALUE, "  日程  ", 1300, 1900, false, 10, 12,
        "  原地点  ", "", "\n原备注\n", Int.MIN_VALUE, 30, ScheduleRepeatRule.CUSTOM, 3,
        "2,1,2", 2000, "Europe/Paris", Long.MIN_VALUE, Long.MAX_VALUE)
}
