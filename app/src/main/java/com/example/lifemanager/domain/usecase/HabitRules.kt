package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.HabitPeriodProgress
import com.example.lifemanager.domain.model.HabitRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

object HabitRules {
    fun validate(
        name: String,
        frequencyType: HabitFrequencyType,
        frequencyValue: Int,
        customDaysOfWeek: Set<DayOfWeek>,
    ): String? = when {
        name.isBlank() -> "习惯名称不能为空"
        frequencyType == HabitFrequencyType.CUSTOM && customDaysOfWeek.isEmpty() -> "请选择至少一个星期几"
        frequencyType == HabitFrequencyType.WEEKLY && frequencyValue !in 1..7 -> "每周目标应在1到7之间"
        frequencyType == HabitFrequencyType.MONTHLY && frequencyValue !in 1..31 -> "每月目标应在1到31之间"
        else -> null
    }

    fun isExpectedOn(habit: Habit, date: LocalDate): Boolean {
        if (date < habit.startDate) return false
        return when (habit.frequencyType) {
            HabitFrequencyType.DAILY,
            HabitFrequencyType.WEEKLY,
            HabitFrequencyType.MONTHLY,
            -> true
            HabitFrequencyType.CUSTOM -> date.dayOfWeek in habit.customDaysOfWeek
        }
    }

    fun periodProgress(
        habit: Habit,
        records: List<HabitRecord>,
        referenceDate: LocalDate,
    ): HabitPeriodProgress {
        val (start, end, target) = when (habit.frequencyType) {
            HabitFrequencyType.DAILY,
            HabitFrequencyType.CUSTOM,
            -> Triple(referenceDate, referenceDate, if (isExpectedOn(habit, referenceDate)) 1 else 0)
            HabitFrequencyType.WEEKLY -> Triple(weekStart(referenceDate), weekStart(referenceDate).plusDays(6), habit.frequencyValue)
            HabitFrequencyType.MONTHLY -> {
                val month = YearMonth.from(referenceDate)
                Triple(month.atDay(1), month.atEndOfMonth(), habit.frequencyValue)
            }
        }
        val completed = recordDates(habit, records)
            .count { it in start..end && isExpectedOn(habit, it) }
            .coerceAtMost(target)
        return HabitPeriodProgress(
            completed = completed,
            target = target,
            isComplete = target > 0 && completed >= target,
        )
    }

    fun currentStreak(habit: Habit, records: List<HabitRecord>, today: LocalDate): Int = when (habit.frequencyType) {
        HabitFrequencyType.DAILY,
        HabitFrequencyType.CUSTOM,
        -> currentExpectedDayStreak(habit, recordDates(habit, records), today)
        HabitFrequencyType.WEEKLY -> currentPeriodStreak(habit, records, weekStart(today)) { it.minusWeeks(1) }
        HabitFrequencyType.MONTHLY -> currentPeriodStreak(habit, records, YearMonth.from(today).atDay(1)) {
            YearMonth.from(it).minusMonths(1).atDay(1)
        }
    }

    fun longestStreak(habit: Habit, records: List<HabitRecord>): Int {
        val dates = recordDates(habit, records)
        val latest = dates.maxOrNull() ?: return 0
        return when (habit.frequencyType) {
            HabitFrequencyType.DAILY,
            HabitFrequencyType.CUSTOM,
            -> longestExpectedDayStreak(habit, dates, latest)
            HabitFrequencyType.WEEKLY -> longestPeriodStreak(habit, records, weekStart(habit.startDate), weekStart(latest)) { it.plusWeeks(1) }
            HabitFrequencyType.MONTHLY -> longestPeriodStreak(
                habit,
                records,
                YearMonth.from(habit.startDate).atDay(1),
                YearMonth.from(latest).atDay(1),
            ) { YearMonth.from(it).plusMonths(1).atDay(1) }
        }
    }

    private fun currentExpectedDayStreak(habit: Habit, dates: Set<LocalDate>, today: LocalDate): Int {
        var date = expectedOnOrBefore(habit, today) ?: return 0
        var streak = 0
        while (date >= habit.startDate && date in dates) {
            streak += 1
            date = expectedOnOrBefore(habit, date.minusDays(1)) ?: break
        }
        return streak
    }

    private fun longestExpectedDayStreak(habit: Habit, dates: Set<LocalDate>, latest: LocalDate): Int {
        var streak = 0
        var longest = 0
        var date = expectedOnOrAfter(habit, habit.startDate, latest) ?: return 0
        while (date <= latest) {
            if (date in dates) {
                streak += 1
                longest = maxOf(longest, streak)
            } else {
                streak = 0
            }
            if (date == latest) break
            date = expectedOnOrAfter(habit, date.plusDays(1), latest) ?: break
        }
        return longest
    }

    private fun expectedOnOrAfter(habit: Habit, date: LocalDate, lastDate: LocalDate): LocalDate? {
        var candidate = maxOf(date, habit.startDate)
        while (candidate <= lastDate) {
            if (isExpectedOn(habit, candidate)) return candidate
            if (candidate == lastDate) break
            candidate = candidate.plusDays(1)
        }
        return null
    }

    private fun currentPeriodStreak(
        habit: Habit,
        records: List<HabitRecord>,
        initialPeriodStart: LocalDate,
        previousPeriodStart: (LocalDate) -> LocalDate,
    ): Int {
        var periodStart = initialPeriodStart
        var streak = 0
        while (periodEnd(habit.frequencyType, periodStart) >= habit.startDate) {
            if (!periodProgress(habit, records, periodStart).isComplete) break
            streak += 1
            periodStart = previousPeriodStart(periodStart)
        }
        return streak
    }

    private fun longestPeriodStreak(
        habit: Habit,
        records: List<HabitRecord>,
        firstPeriodStart: LocalDate,
        lastPeriodStart: LocalDate,
        nextPeriodStart: (LocalDate) -> LocalDate,
    ): Int {
        var periodStart = firstPeriodStart
        var streak = 0
        var longest = 0
        while (periodStart <= lastPeriodStart) {
            if (periodProgress(habit, records, periodStart).isComplete) {
                streak += 1
                longest = maxOf(longest, streak)
            } else {
                streak = 0
            }
            periodStart = nextPeriodStart(periodStart)
        }
        return longest
    }

    private fun expectedOnOrBefore(habit: Habit, date: LocalDate): LocalDate? {
        var candidate = date
        while (candidate >= habit.startDate) {
            if (isExpectedOn(habit, candidate)) return candidate
            candidate = candidate.minusDays(1)
        }
        return null
    }

    private fun periodEnd(frequencyType: HabitFrequencyType, periodStart: LocalDate): LocalDate = when (frequencyType) {
        HabitFrequencyType.WEEKLY -> periodStart.plusDays(6)
        HabitFrequencyType.MONTHLY -> YearMonth.from(periodStart).atEndOfMonth()
        else -> periodStart
    }

    private fun recordDates(habit: Habit, records: List<HabitRecord>): Set<LocalDate> = records
        .asSequence()
        .filter { it.habitId == habit.id && it.date >= habit.startDate }
        .map(HabitRecord::date)
        .toSet()

    private fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
}
