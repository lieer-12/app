package com.example.lifemanager.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.usecase.ScheduleRules
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val reminderScheduler: ScheduleReminderSchedulerContract,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val viewMode = MutableStateFlow(CalendarViewMode.MONTH)
    private val editor = MutableStateFlow(ScheduleEditorState())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val schedules = repository.observeSchedules().catch { error ->
        errorMessage.value = "读取日程失败：${error.message ?: "未知错误"}"
        emit(emptyList())
    }

    val uiState: StateFlow<ScheduleUiState> = combine(schedules, selectedDate, viewMode, editor, errorMessage) {
            scheduleList, date, mode, editorState, error ->
        val (start, end) = visibleRange(date, mode)
        val occurrences = scheduleList.flatMap { schedule ->
            ScheduleRules.occurrencesInRange(schedule, start, end)
        }
        ScheduleUiState(
            schedules = scheduleList,
            occurrences = occurrences,
            selectedDate = date,
            viewMode = mode,
            editor = editorState,
            errorMessage = error,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleUiState())

    fun selectDate(date: LocalDate, switchToDay: Boolean = false) {
        selectedDate.value = date
        if (switchToDay) viewMode.value = CalendarViewMode.DAY
    }

    fun selectViewMode(mode: CalendarViewMode) { viewMode.value = mode }
    fun previousPeriod() = movePeriod(-1)
    fun nextPeriod() = movePeriod(1)

    fun openEditor(schedule: Schedule? = null) {
        val zone = ZoneId.systemDefault()
        val defaultStart = selectedDate.value.atTime(9, 0).atZone(zone).toInstant()
        editor.value = ScheduleEditorState(
            isOpen = true,
            editingId = schedule?.id,
            title = schedule?.title.orEmpty(),
            isAllDay = schedule?.isAllDay ?: false,
            startAt = schedule?.startAt ?: defaultStart,
            endAt = schedule?.endAt ?: defaultStart.plusSeconds(60 * 60),
            allDayStartDate = schedule?.allDayStartDate ?: selectedDate.value,
            allDayEndDate = schedule?.allDayEndDate ?: selectedDate.value,
            location = schedule?.location.orEmpty(),
            participants = schedule?.participants.orEmpty(),
            note = schedule?.note.orEmpty(),
            color = schedule?.color ?: 0xFF00695C.toInt(),
            reminderMinutes = schedule?.reminderMinutes?.toString().orEmpty(),
            repeatRule = schedule?.repeatRule ?: ScheduleRepeatRule.NONE,
        )
    }

    fun closeEditor() { editor.value = ScheduleEditorState() }
    fun onTitleChanged(value: String) = updateEditor { it.copy(title = value, validationMessage = null) }
    fun onAllDayChanged(value: Boolean) = updateEditor { it.copy(isAllDay = value, validationMessage = null) }
    fun onStartChanged(value: Instant) = updateEditor { it.copy(startAt = value, validationMessage = null) }
    fun onEndChanged(value: Instant) = updateEditor { it.copy(endAt = value, validationMessage = null) }
    fun onAllDayDatesChanged(start: LocalDate, end: LocalDate) = updateEditor { it.copy(allDayStartDate = start, allDayEndDate = end, validationMessage = null) }
    fun onLocationChanged(value: String) = updateEditor { it.copy(location = value) }
    fun onParticipantsChanged(value: String) = updateEditor { it.copy(participants = value) }
    fun onNoteChanged(value: String) = updateEditor { it.copy(note = value) }
    fun onColorChanged(value: Int) = updateEditor { it.copy(color = value) }
    fun onReminderChanged(value: String) = updateEditor { it.copy(reminderMinutes = value) }
    fun onRepeatRuleChanged(value: ScheduleRepeatRule) = updateEditor { it.copy(repeatRule = value) }

    fun saveSchedule(force: Boolean = false) {
        val current = editor.value
        val candidate = current.toSchedule()
        ScheduleRules.validate(candidate)?.let { message ->
            updateEditor { it.copy(validationMessage = message) }
            return
        }
        updateEditor { it.copy(isSaving = true, validationMessage = null) }
        viewModelScope.launch(dispatcher) {
            try {
                val conflicts = ScheduleRules.conflictsFor(candidate, repository.getSchedules())
                if (conflicts.isNotEmpty() && !force) {
                    updateEditor { it.copy(isSaving = false, awaitingConflictConfirmation = true, validationMessage = "与 ${conflicts.joinToString { item -> item.title }} 时间重叠") }
                    return@launch
                }
                val id = repository.saveSchedule(candidate)
                val saved = candidate.copy(id = id)
                reminderScheduler.cancel(id)
                reminderScheduler.schedule(saved)
                editor.value = ScheduleEditorState()
            } catch (error: Exception) {
                errorMessage.value = "保存日程失败：${error.message ?: "未知错误"}"
                updateEditor { it.copy(isSaving = false) }
            }
        }
    }

    fun confirmSaveDespiteConflicts() = saveSchedule(force = true)

    fun deleteSchedule(id: Long) {
        viewModelScope.launch(dispatcher) {
            try {
                repository.deleteSchedule(id)
                reminderScheduler.cancel(id)
                closeEditor()
            } catch (error: Exception) {
                errorMessage.value = "删除日程失败：${error.message ?: "未知错误"}"
            }
        }
    }

    private fun movePeriod(amount: Long) {
        selectedDate.value = when (viewMode.value) {
            CalendarViewMode.MONTH -> selectedDate.value.plusMonths(amount)
            CalendarViewMode.WEEK -> selectedDate.value.plusWeeks(amount)
            CalendarViewMode.DAY -> selectedDate.value.plusDays(amount)
        }
    }

    private fun visibleRange(date: LocalDate, mode: CalendarViewMode): Pair<LocalDate, LocalDate> = when (mode) {
        CalendarViewMode.MONTH -> YearMonth.from(date).let { it.atDay(1) to it.atEndOfMonth() }
        CalendarViewMode.WEEK -> date.minusDays((date.dayOfWeek.value - 1).toLong()) to date.plusDays((7 - date.dayOfWeek.value).toLong())
        CalendarViewMode.DAY -> date to date
    }

    private fun ScheduleEditorState.toSchedule(): Schedule {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return Schedule(
            id = editingId ?: 0,
            title = title,
            startAt = if (isAllDay) null else startAt,
            endAt = if (isAllDay) null else endAt,
            isAllDay = isAllDay,
            allDayStartDate = if (isAllDay) allDayStartDate else null,
            allDayEndDate = if (isAllDay) allDayEndDate else null,
            location = location,
            participants = participants,
            note = note,
            color = color,
            reminderMinutes = reminderMinutes.toIntOrNull(),
            repeatRule = repeatRule,
            timeZone = zone.id,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun updateEditor(transform: (ScheduleEditorState) -> ScheduleEditorState) {
        editor.value = transform(editor.value)
    }
}
