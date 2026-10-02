package com.example.lifemanager.ui.habit

import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.HabitPeriodProgress
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class HabitTab { TASKS, STATS }

data class HabitEditorState(
    val isOpen: Boolean = false,
    val editingId: Long? = null,
    val createdAt: Instant? = null,
    val name: String = "",
    val iconKey: String = "Check",
    val color: Int = 0xFF00695C.toInt(),
    val frequencyType: HabitFrequencyType = HabitFrequencyType.DAILY,
    val frequencyValue: String = "1",
    val customDaysOfWeek: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate = LocalDate.now(),
    val note: String = "",
    val validationMessage: String? = null,
    val isSaving: Boolean = false,
)

data class HabitCard(
    val habit: Habit,
    val completedToday: Boolean,
    val canToggleToday: Boolean,
    val currentStreak: Int,
    val progress: HabitPeriodProgress,
)

data class HabitCalendarDay(
    val date: LocalDate,
    val isExpected: Boolean,
    val isCompleted: Boolean,
)

data class HabitHeatmapCell(
    val date: LocalDate,
    val count: Int,
)

data class HabitStatistics(
    val habit: Habit,
    val currentStreak: Int,
    val longestStreak: Int,
    val periodProgress: HabitPeriodProgress,
    val calendarDays: List<HabitCalendarDay>,
    val heatmapCells: List<HabitHeatmapCell>,
)

data class HabitUiState(
    val habits: List<Habit> = emptyList(),
    val cards: List<HabitCard> = emptyList(),
    val selectedTab: HabitTab = HabitTab.TASKS,
    val selectedStatsHabitId: Long? = null,
    val statistics: HabitStatistics? = null,
    val visibleMonth: YearMonth = YearMonth.now(),
    val editor: HabitEditorState = HabitEditorState(),
    val errorMessage: String? = null,
    val isLoading: Boolean = true,
)
