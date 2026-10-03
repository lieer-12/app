package com.example.lifemanager.ui.habit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitFrequencyType
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.domain.usecase.HabitRules
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModel @Inject constructor(
    private val repository: HabitRepository,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val access: GenerationAccess,
) : ViewModel() {
    private val visibleMonth = MutableStateFlow(YearMonth.now())
    private val state = MutableStateFlow(HabitUiState())
    val uiState = state.asStateFlow()
    private var snapshot: Snapshot? = null // Only read/published on Main, never late-stamp emitted DTOs.

    init {
        viewModelScope.launch(dispatcher) {
            try {
                combine(access.generations.distinctUntilChanged(), access.maintenance, visibleMonth) { generation, phase, month ->
                    ReadContext(generation, phase, month)
                }.collectLatest { context ->
                    withContext(Dispatchers.Main.immediate) {
                        if (state.value.generation != null && state.value.generation != context.generation) invalidateSnapshot()
                        state.update { it.copy(isMaintaining = context.phase != MaintenanceState.IDLE) }
                    }
                    if (context.phase != MaintenanceState.IDLE) return@collectLatest
                    try {
                        // Observer emissions are invalidations, not values to tag with a newer generation.
                        repository.observeHabits().flatMapLatest { habits ->
                            val (start, end) = recordRange(habits, context.month)
                            repository.observeRecords(start, end).map { Unit }
                        }.collect {
                            access.read({
                                val habits = repository.observeHabits().first()
                                val (start, end) = recordRange(habits, context.month)
                                Snapshot(habits, repository.observeRecords(start, end).first(), context.month)
                            }) { token, fresh ->
                                if (state.value.generation != null && state.value.generation != token) invalidateSnapshot()
                                snapshot = fresh
                                recompose(state.value.copy(generation = token, isAvailable = true, isLoading = false))
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (error !is MaintenanceBusyException && error !is StaleGenerationException) {
                            access.publishResult(context.generation) {
                                state.update { it.copy(isAvailable = false, isLoading = false, errorMessage = "读取习惯或打卡记录失败，请重新打开页面") }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) {
                    state.update { it.copy(isAvailable = false, isLoading = false, errorMessage = "读取数据世代失败，请重新启动应用并检查数据") }
                }
            }
        }
    }

    fun selectTab(tab: HabitTab) {
        state.update { it.copy(selectedTab = tab) }
    }

    fun previousMonth() {
        visibleMonth.value = visibleMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        visibleMonth.value = visibleMonth.value.plusMonths(1)
    }

    fun selectStatsHabit(id: Long) {
        recompose(state.value.copy(selectedStatsHabitId = id))
    }

    fun openEditor(habit: Habit? = null, generation: DataGeneration? = uiState.value.generation) {
        if (state.value.editor.isSaving || !state.value.isAvailable || generation != state.value.generation) return
        val token = eventToken(generation) ?: return
        if (habit != null && state.value.habits.none { it == habit }) return
        state.update { it.copy(editor = HabitEditorState(
            generation = token,
            isOpen = true,
            editingId = habit?.id,
            createdAt = habit?.createdAt,
            name = habit?.name.orEmpty(),
            iconKey = habit?.iconKey ?: "Check",
            color = habit?.color ?: 0xFF00695C.toInt(),
            frequencyType = habit?.frequencyType ?: HabitFrequencyType.DAILY,
            frequencyValue = habit?.frequencyValue?.toString() ?: "1",
            customDaysOfWeek = habit?.customDaysOfWeek.orEmpty(),
            startDate = habit?.startDate ?: LocalDate.now(),
            note = habit?.note.orEmpty(),
        )) }
    }

    fun closeEditor(generation: DataGeneration? = state.value.editor.generation) {
        if (!state.value.editor.isSaving && state.value.editor.generation == generation) state.update { it.copy(editor = HabitEditorState()) }
    }

    fun onNameChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(name = value, validationMessage = null) }
    fun onIconChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(iconKey = value) }
    fun onColorChanged(value: Int, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(color = value) }
    fun onFrequencyChanged(value: HabitFrequencyType, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) {
        it.copy(frequencyType = value, validationMessage = null)
    }
    fun onFrequencyValueChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(frequencyValue = value, validationMessage = null) }
    fun onCustomDayToggled(day: DayOfWeek, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) {
        it.copy(customDaysOfWeek = it.customDaysOfWeek.toggle(day), validationMessage = null)
    }
    fun onStartDateChanged(value: LocalDate, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(startDate = value) }
    fun onNoteChanged(value: String, generation: DataGeneration? = state.value.editor.generation) = updateEditor(generation) { it.copy(note = value) }

    fun saveHabit(generation: DataGeneration? = state.value.editor.generation) {
        val current = state.value.editor
        if (!current.isOpen || current.isSaving || !state.value.isAvailable || generation != current.generation) return
        val token = eventToken(current.generation) ?: return
        val frequencyValue = current.frequencyValue.toIntOrNull() ?: 0
        HabitRules.validate(
            current.name,
            current.frequencyType,
            frequencyValue,
            current.customDaysOfWeek,
        )?.let { message ->
            updateEditor { it.copy(validationMessage = message) }
            return
        }
        val now = Instant.now()
        val habit = Habit(
            id = current.editingId ?: 0,
            name = current.name,
            iconKey = current.iconKey,
            color = current.color,
            frequencyType = current.frequencyType,
            frequencyValue = frequencyValue,
            customDaysOfWeek = current.customDaysOfWeek,
            startDate = current.startDate,
            note = current.note,
            createdAt = current.createdAt ?: now,
            updatedAt = now,
        )
        updateEditor { it.copy(isSaving = true, validationMessage = null) }
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) {
                    if (current.editingId != null) require(repository.observeHabits().first().any { it.id == current.editingId }) { "习惯已删除，请重新打开页面" }
                    repository.saveHabit(habit)
                    withContext(Dispatchers.Main.immediate) {
                        state.update { it.copy(editor = HabitEditorState(), errorMessage = null) }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "保存习惯失败", error)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    state.update { if (it.editor.generation == token) it.copy(editor = it.editor.copy(isSaving = false)) else it }
                }
            }
        }
    }

    fun deleteHabit(id: Long, generation: DataGeneration? = uiState.value.generation) {
        if (!state.value.isAvailable || state.value.editor.isSaving || generation != state.value.generation) return
        val token = eventToken(generation) ?: return
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) {
                    repository.deleteHabit(id)
                    withContext(Dispatchers.Main.immediate) {
                        recompose(state.value.copy(
                            selectedStatsHabitId = state.value.selectedStatsHabitId.takeUnless { it == id },
                            editor = state.value.editor.takeUnless { it.editingId == id } ?: HabitEditorState(),
                            errorMessage = null,
                        ))
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "删除习惯失败", error)
            }
        }
    }

    fun toggleToday(habitId: Long, generation: DataGeneration? = uiState.value.generation) {
        if (!state.value.isAvailable || generation != state.value.generation) return
        val token = eventToken(generation) ?: return
        val card = uiState.value.cards.firstOrNull { it.habit.id == habitId }
        if (card == null || !card.canToggleToday) {
            state.update { it.copy(errorMessage = "今天不能打卡：习惯不存在、尚未开始或不是指定打卡日") }
            return
        }
        state.update { it.copy(errorMessage = null) }
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) { repository.toggleRecord(habitId, LocalDate.now()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "更新打卡失败", error)
            }
        }
    }

    private fun updateEditor(generation: DataGeneration? = state.value.editor.generation, transform: (HabitEditorState) -> HabitEditorState) {
        state.update { if (it.editor.isOpen && !it.editor.isSaving && it.editor.generation == generation) it.copy(editor = transform(it.editor)) else it }
    }

    private suspend fun reportFailure(token: DataGeneration, action: String, error: Exception) {
        if (error is StaleGenerationException) return
        try {
            access.publishResult(token) { state.update { it.copy(errorMessage = "$action：${error.message ?: "未知错误"}") } }
        } catch (unavailable: Exception) {
            if (unavailable is CancellationException) throw unavailable
            // Cannot validate an old result when the persisted generation is unreadable. Fail closed.
            withContext(Dispatchers.Main.immediate) {
                // Preserve the snapshot's last verified tag so a subsequent replacement still clears its draft.
                state.update { if (it.generation == token) it.copy(isAvailable = false, isLoading = false,
                    errorMessage = "读取数据世代失败，请重新启动应用并检查数据") else it }
            }
        }
    }

    private fun eventToken(generation: DataGeneration?): DataGeneration? = try {
        access.eventToken(generation)
    } catch (error: IllegalStateException) {
        state.update { it.copy(errorMessage = error.message) }
        null
    }

    private fun invalidateSnapshot() {
        snapshot = null
        state.update { it.copy(generation = null, habits = emptyList(), cards = emptyList(), statistics = null,
            selectedStatsHabitId = null, editor = HabitEditorState(), errorMessage = null, isAvailable = false, isLoading = true) }
    }

    private fun recompose(controls: HabitUiState) {
        val data = snapshot ?: return
        state.value = composeState(data.habits, data.records, data.month, controls.selectedTab,
            controls.selectedStatsHabitId, controls.editor, controls.errorMessage).copy(
            generation = controls.generation, isMaintaining = controls.isMaintaining, isAvailable = controls.isAvailable,
        )
    }

    private fun composeState(
        habitList: List<Habit>,
        recordList: List<HabitRecord>,
        month: YearMonth,
        tab: HabitTab,
        statsId: Long?,
        editorState: HabitEditorState,
        error: String?,
    ): HabitUiState {
        val today = LocalDate.now()
        val cards = habitList.map { habit ->
            val completedToday = recordList.any { it.habitId == habit.id && it.date == today }
            HabitCard(
                habit = habit,
                completedToday = completedToday,
                canToggleToday = completedToday || HabitRules.isExpectedOn(habit, today),
                currentStreak = HabitRules.currentStreak(habit, recordList, today),
                progress = HabitRules.periodProgress(habit, recordList, today),
            )
        }
        val selectedHabit = habitList.firstOrNull { it.id == statsId } ?: habitList.firstOrNull()
        return HabitUiState(
            habits = habitList,
            cards = cards,
            selectedTab = tab,
            selectedStatsHabitId = selectedHabit?.id,
            statistics = selectedHabit?.let { habit -> statisticsFor(habit, recordList, month, today) },
            visibleMonth = month,
            editor = editorState,
            errorMessage = error,
            isLoading = false,
        )
    }

    private fun statisticsFor(
        habit: Habit,
        records: List<HabitRecord>,
        month: YearMonth,
        today: LocalDate,
    ): HabitStatistics {
        val dates = records.asSequence().filter { it.habitId == habit.id }.map { it.date }.toSet()
        val calendarDays = (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            HabitCalendarDay(date, HabitRules.isExpectedOn(habit, date), date in dates)
        }
        val heatmapStart = today.minusDays((52 * 7 + today.dayOfWeek.value - 1).toLong())
        val heatmapCells = (0 until 53 * 7).map { offset ->
            val date = heatmapStart.plusDays(offset.toLong())
            HabitHeatmapCell(date, if (date in dates) 1 else 0)
        }
        return HabitStatistics(
            habit = habit,
            currentStreak = HabitRules.currentStreak(habit, records, today),
            longestStreak = HabitRules.longestStreak(habit, records),
            periodProgress = HabitRules.periodProgress(habit, records, today),
            calendarDays = calendarDays,
            heatmapCells = heatmapCells,
        )
    }

    private fun recordRange(habits: List<Habit>, month: YearMonth): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        // Changing a start date must not hide legacy entries from the month/heatmap or today's undo.
        val heatmapStart = today.minusDays((52 * 7 + today.dayOfWeek.value - 1).toLong())
        val start = minOf(habits.minOfOrNull(Habit::startDate) ?: today, month.atDay(1), heatmapStart)
        return start to maxOf(today, month.atEndOfMonth())
    }

    private fun Set<DayOfWeek>.toggle(day: DayOfWeek): Set<DayOfWeek> =
        if (day in this) this - day else this + day

    private data class Snapshot(val habits: List<Habit>, val records: List<HabitRecord>, val month: YearMonth)
    private data class ReadContext(val generation: DataGeneration, val phase: MaintenanceState, val month: YearMonth)
}
