package com.example.lifemanager.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import com.example.lifemanager.domain.usecase.ScheduleRules
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val reminderScheduler: ScheduleReminderSchedulerContract,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val access: GenerationAccess,
) : ViewModel() {
    private val state = MutableStateFlow(ScheduleUiState())
    val uiState = state.asStateFlow()
    // Main owns all published state and notification ordering.
    private var observedGeneration: DataGeneration? = null
    private var notificationSequence = 0L
    private var notificationGeneration: DataGeneration? = null
    private var activeEditorOperation: Any? = null

    init {
        viewModelScope.launch {
            access.maintenance.collect { phase ->
                state.update { it.copy(isMaintaining = phase != MaintenanceState.IDLE) }
            }
        }
        viewModelScope.launch(dispatcher) {
            var listenerGeneration: DataGeneration? = null
            try {
                access.generations.collectLatest { generation ->
                    listenerGeneration = generation
                    withContext(Dispatchers.Main.immediate) {
                        observeGeneration(generation)
                    }
                    access.maintenance.collectLatest { phase ->
                        if (phase == MaintenanceState.IDLE) {
                            try {
                                // Room emissions only invalidate; perform a fresh finite query under admission.
                                repository.observeSchedules().collect {
                                    access.read({ repository.getSchedules() }) { token, fresh -> publishSnapshot(token, fresh) }
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                reportFailure(generation, "读取日程失败", error, unavailable = true)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val failedGeneration = listenerGeneration
                withContext(Dispatchers.Main.immediate) { generationReadFailed(failedGeneration) }
            }
        }
    }

    fun selectDate(date: LocalDate, switchToDay: Boolean = false, generation: DataGeneration? = state.value.generation) {
        if (!canUsePage(generation)) return
        recompose(state.value.copy(selectedDate = date, viewMode = if (switchToDay) CalendarViewMode.DAY else state.value.viewMode))
    }

    fun selectViewMode(mode: CalendarViewMode, generation: DataGeneration? = state.value.generation) {
        if (canUsePage(generation)) recompose(state.value.copy(viewMode = mode))
    }
    fun previousPeriod(generation: DataGeneration? = state.value.generation) = movePeriod(-1, generation)
    fun nextPeriod(generation: DataGeneration? = state.value.generation) = movePeriod(1, generation)

    fun openEditor(schedule: Schedule? = null, generation: DataGeneration? = state.value.generation) {
        if (!canUsePage(generation) || state.value.editor.isSaving) return
        val token = eventToken(generation) ?: return
        if (schedule != null && state.value.schedules.none { it == schedule }) return
        openAdmittedEditor(schedule, token)
    }

    private fun openAdmittedEditor(schedule: Schedule?, generation: DataGeneration) {
        val zone = ZoneId.systemDefault()
        val date = state.value.selectedDate
        val defaultStart = date.atTime(9, 0).atZone(zone).toInstant()
        state.update { it.copy(editor = ScheduleEditorState(
            generation = generation, isOpen = true, editingId = schedule?.id,
            title = schedule?.title.orEmpty(), isAllDay = schedule?.isAllDay ?: false,
            startAt = schedule?.startAt ?: defaultStart, endAt = schedule?.endAt ?: defaultStart.plusSeconds(60 * 60),
            allDayStartDate = schedule?.allDayStartDate ?: date, allDayEndDate = schedule?.allDayEndDate ?: date,
            location = schedule?.location.orEmpty(), participants = schedule?.participants.orEmpty(),
            note = schedule?.note.orEmpty(), color = schedule?.color ?: 0xFF00695C.toInt(),
            reminderMinutes = schedule?.reminderMinutes?.toString().orEmpty(),
            repeatRule = schedule?.repeatRule ?: ScheduleRepeatRule.NONE,
        )) }
    }

    fun closeEditor(generation: DataGeneration? = state.value.editor.generation) {
        val previous = state.value.editor
        if (!previous.isOpen || previous.isSaving || generation == null || previous.generation != generation ||
            generation != state.value.generation || access.maintenance.value != MaintenanceState.IDLE) return
        state.update { it.copy(editor = ScheduleEditorState()) }
        drainNotification(previous)
    }

    fun openNotificationDetail(id: Long, arrivalGeneration: DataGeneration? = null) {
        if (id <= 0L) return
        val sequence = ++notificationSequence
        notificationGeneration = arrivalGeneration
        if (arrivalGeneration != null) {
            // Validate the supplied original tag inside lookup's permit before publishing or deferring.
            loadNotificationDetail(id, sequence, arrivalGeneration)
            return
        }
        val generation = state.value.generation
        notificationGeneration = generation
        if (generation != null) {
            if (state.value.editor.isOpen) deferNotification(id, sequence, generation)
            else loadNotificationDetail(id, sequence, generation)
        } else {
            val observedAtArrival = observedGeneration
            // Enter capture's counted admission at arrival, before the first coroutine suspension.
            // Capture is finite; subsequent lookup keeps its token and never captures a newer one.
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val token = access.capture()
                    if (sequence != notificationSequence) return@launch
                    notificationGeneration = token
                    if (state.value.editor.isOpen) deferNotification(id, sequence, token)
                    else loadNotificationDetail(id, sequence, token)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error is MaintenanceBusyException || error is StaleGenerationException) return@launch
                    if (sequence == notificationSequence) generationReadFailed(observedAtArrival)
                }
            }
        }
    }

    private fun deferNotification(id: Long, sequence: Long, generation: DataGeneration) {
        state.update { it.copy(editor = it.editor.copy(pendingNotificationId = id,
            pendingNotificationToken = sequence, pendingNotificationGeneration = generation)) }
    }

    private fun drainNotification(previous: ScheduleEditorState) {
        val id = previous.pendingNotificationId ?: return
        val sequence = previous.pendingNotificationToken ?: return
        val generation = previous.pendingNotificationGeneration ?: return
        loadNotificationDetail(id, sequence, generation)
    }

    private fun loadNotificationDetail(id: Long, sequence: Long, token: DataGeneration) {
        if (sequence != notificationSequence) return
        viewModelScope.launch(dispatcher) {
            try {
                while (true) {
                    try {
                        access.run(token) {
                            val latest = repository.getSchedules()
                            withContext(Dispatchers.Main.immediate) { deliverNotification(id, sequence, token, latest) }
                        }
                        break
                    } catch (_: MaintenanceBusyException) {
                        // Read-only waiting preserves the original token even if maintenance commits.
                        access.maintenance.first { it == MaintenanceState.IDLE }
                        yield()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "读取通知日程失败", error, sequence = sequence)
            }
        }
    }

    private fun deliverNotification(id: Long, sequence: Long, generation: DataGeneration, latest: List<Schedule>) {
        if (sequence != notificationSequence) return
        publishSnapshot(generation, latest)
        if (sequence != notificationSequence) return
        if (state.value.editor.isOpen) {
            deferNotification(id, sequence, generation)
            return
        }
        val target = latest.firstOrNull { it.id == id }
        if (target == null) state.update { it.copy(errorMessage = "通知对应的日程已删除或不存在") }
        else openAdmittedEditor(target, generation)
    }

    fun onTitleChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(title = value) }
    fun onAllDayChanged(value: Boolean, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(isAllDay = value) }
    fun onStartChanged(value: Instant, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(startAt = value) }
    fun onEndChanged(value: Instant, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(endAt = value) }
    fun onAllDayDatesChanged(start: LocalDate, end: LocalDate, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(allDayStartDate = start, allDayEndDate = end) }
    fun onLocationChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(location = value) }
    fun onParticipantsChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(participants = value) }
    fun onNoteChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(note = value) }
    fun onColorChanged(value: Int, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(color = value) }
    fun onReminderChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(reminderMinutes = value) }
    fun onRepeatRuleChanged(value: ScheduleRepeatRule, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(repeatRule = value) }

    fun saveSchedule(force: Boolean = false, generation: DataGeneration? = state.value.editor.generation) {
        val current = state.value.editor
        if (!canUseEditor(generation) || (force && !current.awaitingConflictConfirmation)) return
        val token = eventToken(current.generation) ?: return
        state.update { it.copy(errorMessage = null) }
        val reminderText = current.reminderMinutes.trim()
        if (reminderText.isNotEmpty() && (reminderText.toIntOrNull() == null || reminderText.toInt() < 0)) {
            state.update { it.copy(editor = it.editor.copy(validationMessage = "提醒分钟必须是非负整数，留空可关闭提醒")) }
            return
        }
        ScheduleRules.validate(current.toSchedule())?.let { message ->
            state.update { it.copy(editor = it.editor.copy(validationMessage = message)) }
            return
        }
        val operation = Any().also { activeEditorOperation = it }
        state.update { it.copy(editor = it.editor.copy(isSaving = true, validationMessage = null)) }
        viewModelScope.launch(dispatcher) {
            var committedEditor: ScheduleEditorState? = null
            try {
                access.run(token) {
                    ScheduleOperationCoordinator.run module@ {
                        val latest = repository.getSchedules()
                        val original = current.editingId?.let { id ->
                            requireNotNull(latest.firstOrNull { it.id == id }) { "日程已删除，请关闭旧表单" }
                        }
                        val candidate = current.toSchedule(original)
                        ScheduleRules.validate(candidate)?.let { throw IllegalArgumentException(it) }
                        val conflicts = ScheduleRules.conflictsFor(candidate, latest).sortedBy { it.id }
                        if (conflicts.isNotEmpty() && (!force || conflicts != current.conflictingSchedules)) {
                            withContext(Dispatchers.Main.immediate) {
                                state.update { it.copy(editor = it.editor.copy(isSaving = false,
                                    awaitingConflictConfirmation = true, conflictingSchedules = conflicts,
                                    validationMessage = "与 ${conflicts.joinToString { item -> item.title }} 时间重叠")) }
                            }
                            return@module
                        }
                        val id = repository.saveSchedule(candidate)
                        withContext(Dispatchers.Main.immediate) {
                            committedEditor = state.value.editor
                            state.update { it.copy(editor = ScheduleEditorState()) }
                        }
                        try {
                            reminderScheduler.cancel(id)
                            reminderScheduler.scheduleCurrent(candidate.copy(id = id))
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.Main.immediate) {
                                state.update { it.copy(errorMessage = "日程已保存，但提醒设置失败：${error.message ?: "未知错误"}") }
                            }
                        }
                    }
                }
                // Do not inherit a held global permit into the deferred lookup.
                withContext(Dispatchers.Main.immediate) { committedEditor?.let(::drainNotification) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "保存日程失败", error, editorFailure = true)
            } finally { finishEditorOperation(token, operation) }
        }
    }

    fun confirmSaveDespiteConflicts(generation: DataGeneration? = state.value.editor.generation) =
        saveSchedule(force = true, generation = generation)

    fun deleteSchedule(id: Long, generation: DataGeneration? = state.value.generation) {
        if (!canUsePage(generation) || state.value.editor.isSaving) return
        val token = eventToken(generation) ?: return
        val deletingEditor = state.value.editor.isOpen && state.value.editor.editingId == id
        val operation = if (deletingEditor) Any().also { activeEditorOperation = it } else null
        if (deletingEditor) state.update { it.copy(editor = it.editor.copy(isSaving = true)) }
        viewModelScope.launch(dispatcher) {
            var deletedEditor: ScheduleEditorState? = null
            try {
                access.run(token) {
                    ScheduleOperationCoordinator.run {
                        repository.deleteSchedule(id)
                        withContext(Dispatchers.Main.immediate) {
                            if (state.value.editor.editingId == id) {
                                deletedEditor = state.value.editor
                                state.update { it.copy(editor = ScheduleEditorState()) }
                            }
                        }
                        try { reminderScheduler.cancel(id) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.Main.immediate) {
                                state.update { it.copy(errorMessage = "日程已删除，但提醒清理失败：${error.message ?: "未知错误"}") }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main.immediate) { deletedEditor?.let(::drainNotification) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "删除日程失败", error, editorFailure = deletingEditor)
            } finally { if (operation != null) finishEditorOperation(token, operation) }
        }
    }

    private suspend fun finishEditorOperation(token: DataGeneration, operation: Any) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (activeEditorOperation === operation) {
                activeEditorOperation = null
                state.update { if (it.editor.generation == token) it.copy(editor = it.editor.copy(isSaving = false)) else it }
            }
        }
    }

    private suspend fun reportFailure(
        token: DataGeneration, action: String, error: Exception,
        unavailable: Boolean = false, editorFailure: Boolean = false, sequence: Long? = null,
    ) {
        if (error is MaintenanceBusyException || error is StaleGenerationException) return
        try {
            access.publishResult(token) {
                if (observedGeneration != token || (sequence != null && sequence != notificationSequence)) return@publishResult
                val message = "$action：${error.message ?: "未知错误"}"
                state.update { it.copy(isAvailable = it.isAvailable && !unavailable, isLoading = false,
                    errorMessage = message, editor = if (editorFailure && it.editor.generation == token)
                        it.editor.copy(isSaving = false, validationMessage = message) else it.editor) }
            }
        } catch (unreadable: Exception) {
            if (unreadable is CancellationException) throw unreadable
            withContext(Dispatchers.Main.immediate) {
                if (sequence == null || sequence == notificationSequence) generationReadFailed(token)
            }
        }
    }

    private fun generationReadFailed(token: DataGeneration?) {
        // A delayed old fault cannot disable a newer observed generation before its snapshot arrives.
        if (observedGeneration != token || (state.value.generation != null && state.value.generation != token)) return
        state.update { it.copy(isAvailable = false, isLoading = false,
            errorMessage = "读取数据世代失败，请重新启动应用并检查数据") }
    }

    private fun observeGeneration(token: DataGeneration) {
        val previous = observedGeneration
        if (previous != null && token.value < previous.value) return
        if (observedGeneration != null && observedGeneration != token) {
            invalidateSnapshot(token)
        }
        observedGeneration = token
    }

    private fun publishSnapshot(token: DataGeneration, schedules: List<Schedule>) {
        observeGeneration(token)
        recompose(state.value.copy(generation = token, schedules = schedules, isAvailable = true, isLoading = false))
    }

    private fun invalidateSnapshot(token: DataGeneration) {
        // Keep a newer fixed-token request across a delayed intermediate observation.
        if (notificationGeneration?.let { it.value < token.value } != false) {
            notificationSequence++
            notificationGeneration = null
        }
        activeEditorOperation = null
        state.update { it.copy(generation = null, schedules = emptyList(), occurrences = emptyList(),
            editor = ScheduleEditorState(), isAvailable = false, isLoading = true, errorMessage = null) }
    }

    private fun canUsePage(generation: DataGeneration?) = state.value.isAvailable &&
        generation != null && generation == state.value.generation && access.maintenance.value == MaintenanceState.IDLE

    private fun canUseEditor(generation: DataGeneration?) = canUsePage(generation) &&
        state.value.editor.isOpen && !state.value.editor.isSaving && state.value.editor.generation == generation

    private fun eventToken(generation: DataGeneration?): DataGeneration? = try { access.eventToken(generation) }
    catch (error: IllegalStateException) { state.update { it.copy(errorMessage = error.message) }; null }

    private fun updateEditor(generation: DataGeneration?, transform: (ScheduleEditorState) -> ScheduleEditorState) {
        if (canUseEditor(generation)) state.update { it.copy(editor = transform(it.editor).copy(
            awaitingConflictConfirmation = false, conflictingSchedules = emptyList(), validationMessage = null)) }
    }

    private fun movePeriod(amount: Long, generation: DataGeneration?) {
        if (!canUsePage(generation)) return
        val date = state.value.selectedDate
        recompose(state.value.copy(selectedDate = when (state.value.viewMode) {
            CalendarViewMode.MONTH -> date.plusMonths(amount)
            CalendarViewMode.WEEK -> date.plusWeeks(amount)
            CalendarViewMode.DAY -> date.plusDays(amount)
        }))
    }

    private fun recompose(controls: ScheduleUiState) {
        val (start, end) = visibleRange(controls.selectedDate, controls.viewMode)
        state.value = controls.copy(occurrences = controls.schedules.flatMap { ScheduleRules.occurrencesInRange(it, start, end) })
    }

    private fun visibleRange(date: LocalDate, mode: CalendarViewMode): Pair<LocalDate, LocalDate> = when (mode) {
        CalendarViewMode.MONTH -> YearMonth.from(date).let { it.atDay(1) to it.atEndOfMonth() }
        CalendarViewMode.WEEK -> date.minusDays((date.dayOfWeek.value - 1).toLong()) to date.plusDays((7 - date.dayOfWeek.value).toLong())
        CalendarViewMode.DAY -> date to date
    }

    private fun ScheduleEditorState.toSchedule(original: Schedule? = null): Schedule {
        val now = Instant.now()
        return (original ?: Schedule(title = title, timeZone = ZoneId.systemDefault().id, createdAt = now)).copy(
            id = editingId ?: 0, title = title,
            startAt = if (isAllDay) null else startAt, endAt = if (isAllDay) null else endAt,
            isAllDay = isAllDay, allDayStartDate = if (isAllDay) allDayStartDate else null,
            allDayEndDate = if (isAllDay) allDayEndDate else null,
            location = location, participants = participants, note = note, color = color,
            reminderMinutes = reminderMinutes.trim().toIntOrNull(), repeatRule = repeatRule, updatedAt = now,
        )
    }
}
