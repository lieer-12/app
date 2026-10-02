package com.example.lifemanager.ui.habit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModel @Inject constructor(
    private val repository: HabitRepository,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val visibleMonth = MutableStateFlow(YearMonth.now())
    private val selectedTab = MutableStateFlow(HabitTab.TASKS)
    private val selectedStatsHabitId = MutableStateFlow<Long?>(null)
    private val editor = MutableStateFlow(HabitEditorState())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val habits = repository.observeHabits().catch { error ->
        errorMessage.value = "读取习惯失败：${error.message ?: "未知错误"}"
        emit(emptyList())
    }
    private val records: Flow<List<HabitRecord>> = combine(habits, visibleMonth) { habitList, month ->
        recordRange(habitList, month)
    }.flatMapLatest { (start, end) ->
        repository.observeRecords(start, end).catch { error ->
            errorMessage.value = "读取打卡记录失败：${error.message ?: "未知错误"}"
            emit(emptyList())
        }
    }

    private val habitData = combine(habits, records) { habitList, recordList ->
        habitList to recordList
    }
    private val presentation = combine(
        visibleMonth,
        selectedTab,
        selectedStatsHabitId,
        editor,
        errorMessage,
    ) { month, tab, statsId, editorState, error ->
        Presentation(month, tab, statsId, editorState, error)
    }

    val uiState = combine(habitData, presentation) { (habitList, recordList), controls ->
        composeState(
            habitList,
            recordList,
            controls.month,
            controls.tab,
            controls.statsHabitId,
            controls.editor,
            controls.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HabitUiState())

    fun selectTab(tab: HabitTab) {
        selectedTab.value = tab
    }

    fun previousMonth() {
        visibleMonth.value = visibleMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        visibleMonth.value = visibleMonth.value.plusMonths(1)
    }

    fun selectStatsHabit(id: Long) {
        selectedStatsHabitId.value = id
    }

    fun openEditor(habit: Habit? = null) {
        editor.value = HabitEditorState(
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
        )
    }

    fun closeEditor() {
        editor.value = HabitEditorState()
    }

    fun onNameChanged(value: String) = updateEditor { it.copy(name = value, validationMessage = null) }
    fun onIconChanged(value: String) = updateEditor { it.copy(iconKey = value) }
    fun onColorChanged(value: Int) = updateEditor { it.copy(color = value) }
    fun onFrequencyChanged(value: HabitFrequencyType) = updateEditor {
        it.copy(frequencyType = value, validationMessage = null)
    }
    fun onFrequencyValueChanged(value: String) = updateEditor { it.copy(frequencyValue = value, validationMessage = null) }
    fun onCustomDayToggled(day: DayOfWeek) = updateEditor {
        it.copy(customDaysOfWeek = it.customDaysOfWeek.toggle(day), validationMessage = null)
    }
    fun onStartDateChanged(value: LocalDate) = updateEditor { it.copy(startDate = value) }
    fun onNoteChanged(value: String) = updateEditor { it.copy(note = value) }

    fun saveHabit() {
        val current = editor.value
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
                repository.saveHabit(habit)
                editor.value = HabitEditorState()
            } catch (error: Exception) {
                errorMessage.value = "保存习惯失败：${error.message ?: "未知错误"}"
                updateEditor { it.copy(isSaving = false) }
            }
        }
    }

    fun deleteHabit(id: Long) {
        viewModelScope.launch(dispatcher) {
            try {
                repository.deleteHabit(id)
                if (selectedStatsHabitId.value == id) selectedStatsHabitId.value = null
                closeEditor()
            } catch (error: Exception) {
                errorMessage.value = "删除习惯失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun toggleToday(habitId: Long) {
        val card = uiState.value.cards.firstOrNull { it.habit.id == habitId }
        if (card == null || !card.canToggleToday) {
            errorMessage.value = "今天不能打卡：习惯不存在、尚未开始或不是指定打卡日"
            return
        }
        errorMessage.value = null
        viewModelScope.launch(dispatcher) {
            try {
                repository.toggleRecord(habitId, LocalDate.now())
            } catch (error: Exception) {
                errorMessage.value = "更新打卡失败：${error.message ?: "未知错误"}"
            }
        }
    }

    private fun updateEditor(transform: (HabitEditorState) -> HabitEditorState) {
        editor.value = transform(editor.value)
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

    private data class Presentation(
        val month: YearMonth,
        val tab: HabitTab,
        val statsHabitId: Long?,
        val editor: HabitEditorState,
        val error: String?,
    )
}
