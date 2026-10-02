package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.HabitRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitRulesTest {
    @Test
    fun `blank habit name is rejected after trimming`() {
        assertEquals(
            "习惯名称不能为空",
            HabitRules.validate("   ", HabitFrequencyType.DAILY, 1, emptySet()),
        )
    }

    @Test
    fun `valid daily habit is accepted`() {
        assertNull(HabitRules.validate("阅读", HabitFrequencyType.DAILY, 1, emptySet()))
    }

    @Test
    fun `custom habit requires at least one weekday`() {
        assertEquals(
            "请选择至少一个星期几",
            HabitRules.validate("运动", HabitFrequencyType.CUSTOM, 1, emptySet()),
        )
    }

    @Test
    fun `weekly target must be between one and seven`() {
        assertEquals(
            "每周目标应在1到7之间",
            HabitRules.validate("运动", HabitFrequencyType.WEEKLY, 0, emptySet()),
        )
        assertEquals(
            "每周目标应在1到7之间",
            HabitRules.validate("运动", HabitFrequencyType.WEEKLY, 8, emptySet()),
        )
    }

    @Test
    fun `monthly target must be between one and thirty one`() {
        assertEquals(
            "每月目标应在1到31之间",
            HabitRules.validate("写作", HabitFrequencyType.MONTHLY, 0, emptySet()),
        )
        assertEquals(
            "每月目标应在1到31之间",
            HabitRules.validate("写作", HabitFrequencyType.MONTHLY, 32, emptySet()),
        )
    }

    @Test
    fun `custom habit only expects selected weekdays after its start date`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            startDate = LocalDate.of(2026, 8, 24),
        )

        assertFalse(HabitRules.isExpectedOn(habit, LocalDate.of(2026, 8, 23)))
        assertTrue(HabitRules.isExpectedOn(habit, LocalDate.of(2026, 8, 24)))
        assertFalse(HabitRules.isExpectedOn(habit, LocalDate.of(2026, 8, 25)))
        assertTrue(HabitRules.isExpectedOn(habit, LocalDate.of(2026, 8, 26)))
    }

    @Test
    fun `period progress counts each eligible record date once`() {
        val habit = habit()
        val records = recordsOn(habit, "2026-08-21", "2026-08-22", "2026-08-22", "2026-08-23")

        val progress = HabitRules.periodProgress(habit, records, LocalDate.of(2026, 8, 22))

        assertEquals(1, progress.completed)
        assertEquals(1, progress.target)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `custom period progress ignores records on unselected weekdays`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY),
        )
        val records = recordsOn(habit, "2026-08-24", "2026-08-25")

        val progress = HabitRules.periodProgress(habit, records, LocalDate.of(2026, 8, 25))

        assertEquals(0, progress.completed)
        assertFalse(progress.isComplete)
    }

    @Test
    fun `monthly progress completes after its target distinct dates`() {
        val habit = habit(frequencyType = HabitFrequencyType.MONTHLY, frequencyValue = 3)
        val records = recordsOn(habit, "2026-08-02", "2026-08-14", "2026-08-28")

        val progress = HabitRules.periodProgress(habit, records, LocalDate.of(2026, 8, 28))

        assertEquals(3, progress.completed)
        assertEquals(3, progress.target)
        assertTrue(progress.isComplete)
    }

    @Test
    fun `daily current streak stops at the first missed day`() {
        val habit = habit()
        val records = recordsOn(habit, "2026-08-19", "2026-08-21", "2026-08-22")

        assertEquals(2, HabitRules.currentStreak(habit, records, LocalDate.of(2026, 8, 22)))
    }

    @Test
    fun `custom current streak walks backwards over expected weekdays`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        )
        val records = recordsOn(habit, "2026-08-24", "2026-08-26")

        assertEquals(2, HabitRules.currentStreak(habit, records, LocalDate.of(2026, 8, 26)))
    }

    @Test
    fun `weekly streak counts completed target weeks`() {
        val habit = habit(frequencyType = HabitFrequencyType.WEEKLY, frequencyValue = 2)
        val records = recordsOn(habit, "2026-08-10", "2026-08-12", "2026-08-17", "2026-08-19")

        assertEquals(2, HabitRules.currentStreak(habit, records, LocalDate.of(2026, 8, 22)))
    }

    @Test
    fun `monthly longest streak requires consecutive completed calendar months`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.MONTHLY,
            frequencyValue = 2,
            startDate = LocalDate.of(2026, 1, 1),
        )
        val records = recordsOn(
            habit,
            "2026-01-03", "2026-01-12",
            "2026-02-04", "2026-02-17",
            "2026-04-05", "2026-04-11",
        )

        assertEquals(2, HabitRules.longestStreak(habit, records))
    }

    @Test
    fun `longest daily streak ignores records before habit start date`() {
        val habit = habit(startDate = LocalDate.of(2026, 8, 20))
        val records = recordsOn(habit, "2026-08-18", "2026-08-19", "2026-08-20", "2026-08-21")

        assertEquals(2, HabitRules.longestStreak(habit, records))
    }

    @Test
    fun `custom longest streak advances between selected weekdays`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY),
            startDate = LocalDate.of(2026, 9, 28),
        )
        assertEquals(2, boundedLongestStreak(habit, recordsOn(habit, "2026-09-28", "2026-10-05")))
    }

    @Test
    fun `custom longest streak starts at first selected weekday after start date`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY),
            startDate = LocalDate.of(2026, 9, 29),
        )
        assertEquals(1, boundedLongestStreak(habit, recordsOn(habit, "2026-10-05")))
    }

    @Test
    fun `missing selected weekday breaks longest streak but other weekdays do not`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            startDate = LocalDate.of(2026, 9, 28),
        )
        val records = recordsOn(habit, "2026-09-28", "2026-09-29", "2026-09-30", "2026-10-07")
        assertEquals(2, boundedLongestStreak(habit, records))
    }

    @Test
    fun `unselected legacy record does not add to custom longest streak`() {
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(DayOfWeek.MONDAY),
            startDate = LocalDate.of(2026, 9, 29),
        )
        assertEquals(0, boundedLongestStreak(habit, recordsOn(habit, "2026-09-30")))
    }

    @Test
    fun `longest daily streak handles maximum supported date without overflow`() {
        val habit = habit(startDate = LocalDate.MAX)
        val record = HabitRecord(habitId = habit.id, date = LocalDate.MAX, createdAt = Instant.EPOCH)
        assertEquals(1, boundedLongestStreak(habit, listOf(record)))
    }

    @Test
    fun `custom forward search stops at unselected maximum date without overflow`() {
        val start = LocalDate.MAX.minusDays(1)
        val habit = habit(
            frequencyType = HabitFrequencyType.CUSTOM,
            customDaysOfWeek = setOf(start.dayOfWeek),
            startDate = start,
        )
        val records = listOf(start, LocalDate.MAX).map {
            HabitRecord(habitId = habit.id, date = it, createdAt = Instant.EPOCH)
        }
        assertEquals(1, boundedLongestStreak(habit, records))
    }

    // A broken progression must fail promptly, not hang the entire test worker.
    private fun boundedLongestStreak(habit: Habit, records: List<HabitRecord>): Int {
        val task = FutureTask { HabitRules.longestStreak(habit, records) }
        Thread(task, "habit-streak-regression").apply { isDaemon = true }.start()
        return try {
            task.get(2, TimeUnit.SECONDS)
        } finally {
            task.cancel(true)
        }
    }

    private fun habit(
        frequencyType: HabitFrequencyType = HabitFrequencyType.DAILY,
        frequencyValue: Int = 1,
        customDaysOfWeek: Set<DayOfWeek> = emptySet(),
        startDate: LocalDate = LocalDate.of(2026, 8, 1),
    ) = Habit(
        id = 7,
        name = "阅读",
        frequencyType = frequencyType,
        frequencyValue = frequencyValue,
        customDaysOfWeek = customDaysOfWeek,
        startDate = startDate,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    )

    private fun recordsOn(habit: Habit, vararg dates: String): List<HabitRecord> =
        dates.mapIndexed { index, date ->
            HabitRecord(
                id = index.toLong() + 1,
                habitId = habit.id,
                date = LocalDate.parse(date),
                createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            )
        }
}
