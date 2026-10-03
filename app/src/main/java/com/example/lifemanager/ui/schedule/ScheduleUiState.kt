package com.example.lifemanager.ui.schedule

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleOccurrence
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.maintenance.DataGeneration
import java.time.Instant
import java.time.LocalDate

enum class CalendarViewMode { MONTH, WEEK, DAY }

data class ScheduleEditorState(
    val generation: DataGeneration? = null,
    val isOpen: Boolean = false,
    val editingId: Long? = null,
    val title: String = "",
    val isAllDay: Boolean = false,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val allDayStartDate: LocalDate? = null,
    val allDayEndDate: LocalDate? = null,
    val location: String = "",
    val participants: String = "",
    val note: String = "",
    val color: Int = 0xFF00695C.toInt(),
    val reminderMinutes: String = "15",
    val repeatRule: ScheduleRepeatRule = ScheduleRepeatRule.NONE,
    val validationMessage: String? = null,
    val awaitingConflictConfirmation: Boolean = false,
    val isSaving: Boolean = false,
    val conflictingSchedules: List<Schedule> = emptyList(),
    val pendingNotificationId: Long? = null,
    val pendingNotificationToken: Long? = null,
    val pendingNotificationGeneration: DataGeneration? = null,
)

data class ScheduleUiState(
    val generation: DataGeneration? = null,
    val isAvailable: Boolean = false,
    val isMaintaining: Boolean = false,
    val schedules: List<Schedule> = emptyList(),
    val occurrences: List<ScheduleOccurrence> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val viewMode: CalendarViewMode = CalendarViewMode.MONTH,
    val editor: ScheduleEditorState = ScheduleEditorState(),
    val errorMessage: String? = null,
    val isLoading: Boolean = true,
)
