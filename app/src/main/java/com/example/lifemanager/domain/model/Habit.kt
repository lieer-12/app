package com.example.lifemanager.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

enum class HabitFrequencyType {
    DAILY,
    WEEKLY,
    MONTHLY,
    CUSTOM,
}

data class Habit(
    val id: Long = 0,
    val name: String,
    val iconKey: String = "Check",
    val color: Int = 0xFF00695C.toInt(),
    val frequencyType: HabitFrequencyType = HabitFrequencyType.DAILY,
    val frequencyValue: Int = 1,
    val customDaysOfWeek: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate,
    val note: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class HabitRecord(
    val id: Long = 0,
    val habitId: Long,
    val date: LocalDate,
    val createdAt: Instant,
)

data class HabitPeriodProgress(
    val completed: Int,
    val target: Int,
    val isComplete: Boolean,
)
