package com.example.lifemanager.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.usecase.ScheduleRules
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val reminderScheduler: ScheduleReminderSchedulerContract,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val viewMode = MutableStateFlow(CalendarViewMode.MONTH)
    private val editor = MutableStateFlow(ScheduleEditorState())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val notificationGeneration = AtomicLong()
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
        if (editor.value.isSaving) return
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

    fun closeEditor() {
        if (editor.value.isSaving) return
        drainNotification(editor.getAndUpdate { ScheduleEditorState() })
    }

    fun openNotificationDetail(id: Long) {
        if (id <= 0L) return
        val token = notificationGeneration.incrementAndGet()
        val previous = editor.getAndUpdate {
            if (it.isOpen) it.copy(pendingNotificationId = id, pendingNotificationToken = token) else it
        }
        if (!previous.isOpen) loadNotificationDetail(id, token)
    }

    private fun drainNotification(previous: ScheduleEditorState) {
        val id = previous.pendingNotificationId ?: return
        val token = previous.pendingNotificationToken ?: return
        loadNotificationDetail(id, token)
    }

    private fun loadNotificationDetail(id: Long, token: Long) {
        if (token != notificationGeneration.get()) return
        viewModelScope.launch {
            try {
                val target = withContext(dispatcher) { repository.getSchedules().firstOrNull { it.id == id } }
                if (token != notificationGeneration.get()) return@launch
                val previous = editor.getAndUpdate {
                    if (it.isOpen) it.copy(pendingNotificationId = id, pendingNotificationToken = token) else it
                }
                if (previous.isOpen) return@launch
                if (target == null) errorMessage.value = "通知对应的日程已删除或不存在"
                else openEditor(target)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (token == notificationGeneration.get()) errorMessage.value = "读取通知日程失败：${error.message ?: "未知错误"}"
            }
        }
    }
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
        if (!current.isOpen || current.isSaving || (force && !current.awaitingConflictConfirmation)) return
        errorMessage.value = null
        val reminderText = current.reminderMinutes.trim()
        if (reminderText.isNotEmpty() && (reminderText.toIntOrNull() == null || reminderText.toInt() < 0)) {
            editor.update { it.copy(validationMessage = "提醒分钟必须是非负整数，留空可关闭提醒") }
            return
        }
        val candidate = current.toSchedule()
        ScheduleRules.validate(candidate)?.let { message ->
            editor.update { it.copy(validationMessage = message) }
            return
        }
        editor.update { it.copy(isSaving = true, validationMessage = null) }
        viewModelScope.launch(dispatcher) {
            try {
                ScheduleOperationCoordinator.run {
                    val latest = repository.getSchedules()
                    val original = current.editingId?.let { id ->
                        requireNotNull(latest.firstOrNull { it.id == id }) { "日程已删除，请关闭旧表单" }
                    }
                    val savedCandidate = current.toSchedule(original)
                    ScheduleRules.validate(savedCandidate)?.let { throw IllegalArgumentException(it) }
                    val conflicts = ScheduleRules.conflictsFor(savedCandidate, latest).sortedBy { it.id }
                    if (conflicts.isNotEmpty() && (!force || conflicts != current.conflictingSchedules)) {
                        withContext(Dispatchers.Main.immediate) {
                            editor.update { it.copy(isSaving = false, awaitingConflictConfirmation = true,
                                conflictingSchedules = conflicts,
                                validationMessage = "与 ${conflicts.joinToString { item -> item.title }} 时间重叠") }
                        }
                        return@run
                    }
                    val id = repository.saveSchedule(savedCandidate)
                    val previous = withContext(Dispatchers.Main.immediate) { editor.getAndUpdate { ScheduleEditorState() } }
                    try {
                        reminderScheduler.cancel(id)
                        reminderScheduler.schedule(savedCandidate.copy(id = id))
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errorMessage.value = "日程已保存，但提醒设置失败：${error.message ?: "未知错误"}" }
                    drainNotification(previous)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    errorMessage.value = "保存日程失败：${error.message ?: "未知错误"}"
                    editor.update { it.copy(isSaving = false, validationMessage = errorMessage.value) }
                }
            }
        }
    }

    fun confirmSaveDespiteConflicts() = saveSchedule(force = true)

    fun deleteSchedule(id: Long) {
        if (editor.value.isSaving) return
        if (editor.value.isOpen && editor.value.editingId == id) editor.update { it.copy(isSaving = true) }
        viewModelScope.launch(dispatcher) {
            try {
                ScheduleOperationCoordinator.run {
                    repository.deleteSchedule(id)
                    val previous = withContext(Dispatchers.Main.immediate) {
                        editor.getAndUpdate { if (it.editingId == id) ScheduleEditorState() else it }
                    }
                    try { reminderScheduler.cancel(id) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errorMessage.value = "日程已删除，但提醒清理失败：${error.message ?: "未知错误"}" }
                    if (previous.editingId == id) drainNotification(previous)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    errorMessage.value = "删除日程失败：${error.message ?: "未知错误"}"
                    if (editor.value.editingId == id) editor.update { it.copy(isSaving = false, validationMessage = errorMessage.value) }
                }
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

    private fun ScheduleEditorState.toSchedule(original: Schedule? = null): Schedule {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return (original ?: Schedule(title = title, timeZone = zone.id, createdAt = now)).copy(
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
            reminderMinutes = reminderMinutes.trim().toIntOrNull(),
            repeatRule = repeatRule,
            updatedAt = now,
        )
    }

    private fun updateEditor(transform: (ScheduleEditorState) -> ScheduleEditorState) {
        editor.update { if (!it.isOpen || it.isSaving) it else transform(it).copy(
            awaitingConflictConfirmation = false, conflictingSchedules = emptyList(), validationMessage = null) }
    }
}
