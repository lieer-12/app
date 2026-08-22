package com.example.lifemanager.ui.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.model.HabitFrequencyType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HabitScreen(viewModel: HabitViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HabitContent(
        state = state,
        onTabSelected = viewModel::selectTab,
        onOpenEditor = viewModel::openEditor,
        onCloseEditor = viewModel::closeEditor,
        onToggleToday = viewModel::toggleToday,
        onSelectStatsHabit = viewModel::selectStatsHabit,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onNameChanged = viewModel::onNameChanged,
        onIconChanged = viewModel::onIconChanged,
        onColorChanged = viewModel::onColorChanged,
        onFrequencyChanged = viewModel::onFrequencyChanged,
        onFrequencyValueChanged = viewModel::onFrequencyValueChanged,
        onCustomDayToggled = viewModel::onCustomDayToggled,
        onStartDateChanged = viewModel::onStartDateChanged,
        onNoteChanged = viewModel::onNoteChanged,
        onSave = viewModel::saveHabit,
        onDelete = viewModel::deleteHabit,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitContent(
    state: HabitUiState,
    onTabSelected: (HabitTab) -> Unit,
    onOpenEditor: (com.example.lifemanager.domain.model.Habit?) -> Unit,
    onCloseEditor: () -> Unit,
    onToggleToday: (Long) -> Unit,
    onSelectStatsHabit: (Long) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onNameChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit,
    onColorChanged: (Int) -> Unit,
    onFrequencyChanged: (HabitFrequencyType) -> Unit,
    onFrequencyValueChanged: (String) -> Unit,
    onCustomDayToggled: (DayOfWeek) -> Unit,
    onStartDateChanged: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("打卡") }) },
        floatingActionButton = {
            if (state.selectedTab == HabitTab.TASKS) {
                FloatingActionButton(onClick = { onOpenEditor(null) }) { Text("新增") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.selectedTab.ordinal) {
                HabitTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = { Text(if (tab == HabitTab.TASKS) "任务" else "统计") },
                    )
                }
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (state.selectedTab) {
                HabitTab.TASKS -> HabitTaskList(state.cards, onOpenEditor, onToggleToday)
                HabitTab.STATS -> HabitStats(
                    state = state,
                    onSelectHabit = onSelectStatsHabit,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                )
            }
        }
    }
    if (state.editor.isOpen) {
        HabitEditorDialog(
            editor = state.editor,
            onClose = onCloseEditor,
            onNameChanged = onNameChanged,
            onIconChanged = onIconChanged,
            onColorChanged = onColorChanged,
            onFrequencyChanged = onFrequencyChanged,
            onFrequencyValueChanged = onFrequencyValueChanged,
            onCustomDayToggled = onCustomDayToggled,
            onStartDateChanged = onStartDateChanged,
            onNoteChanged = onNoteChanged,
            onSave = onSave,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun HabitTaskList(
    cards: List<HabitCard>,
    onOpenEditor: (com.example.lifemanager.domain.model.Habit) -> Unit,
    onToggleToday: (Long) -> Unit,
) {
    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有习惯，点击“新增”创建第一项打卡。", textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.habit.id }) { card ->
            Card(onClick = { onOpenEditor(card.habit) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = Color(card.habit.color),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(card.habit.iconKey) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(card.habit.name, style = MaterialTheme.typography.titleMedium)
                        Text(frequencyLabel(card.habit.frequencyType, card.habit.frequencyValue))
                        Text("当前连续 ${card.currentStreak} 次 · ${card.progress.completed}/${card.progress.target}")
                    }
                    Button(
                        onClick = { onToggleToday(card.habit.id) },
                        modifier = Modifier.semantics {
                            contentDescription = if (card.completedToday) "撤销打卡：${card.habit.name}" else "打卡：${card.habit.name}"
                        },
                    ) { Text(if (card.completedToday) "撤销" else "打卡") }
                }
            }
        }
    }
}

@Composable
private fun HabitStats(
    state: HabitUiState,
    onSelectHabit: (Long) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val statistics = state.statistics
    if (statistics == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("创建习惯后即可查看打卡统计。", textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.habits, key = { it.id }) { habit ->
                    FilterChip(
                        selected = habit.id == state.selectedStatsHabitId,
                        onClick = { onSelectHabit(habit.id) },
                        label = { Text(habit.name) },
                    )
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${statistics.habit.name} 的统计", style = MaterialTheme.typography.titleMedium)
                    Text("当前连续：${statistics.currentStreak} 次")
                    Text("最长连续：${statistics.longestStreak} 次")
                    Text("当前周期：${statistics.periodProgress.completed}/${statistics.periodProgress.target}")
                }
            }
        }
        item {
            MonthCalendar(
                month = state.visibleMonth,
                days = statistics.calendarDays,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
            )
        }
        item { Heatmap(statistics.heatmapCells) }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    days: List<HabitCalendarDay>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious) { Text("上月") }
                Text("${month.year}年${month.monthValue}月", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton(onClick = onNext) { Text("下月") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(label, Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            val blanks = List(month.atDay(1).dayOfWeek.value - 1) { null }
            (blanks + days).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) Spacer(Modifier.weight(1f).height(44.dp)) else CalendarDay(day)
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f).height(44.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CalendarDay(day: HabitCalendarDay) {
    val background = when {
        day.isCompleted -> MaterialTheme.colorScheme.primary
        day.isExpected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val label = "${day.date.year}年${day.date.monthValue}月${day.date.dayOfMonth}日，${if (day.isCompleted) "已打卡" else if (day.isExpected) "应打卡，未完成" else "非打卡日"}"
    Box(
        modifier = Modifier.weight(1f).height(44.dp).padding(3.dp).background(background).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(day.date.dayOfMonth.toString(), textAlign = TextAlign.Center) }
}

@Composable
private fun Heatmap(cells: List<HabitHeatmapCell>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近 53 周热力图", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                cells.chunked(7).forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        week.forEach { cell ->
                            val background = if (cell.count == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
                            Box(
                                modifier = Modifier.size(12.dp).background(background).semantics {
                                    contentDescription = "${cell.date}，${if (cell.count == 0) "无记录" else "1 次"}"
                                },
                            )
                        }
                    }
                }
            }
            Text("图例：无记录 · 1 次 · 2 次及以上（每天最多一条记录）")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitEditorDialog(
    editor: HabitEditorState,
    onClose: () -> Unit,
    onNameChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit,
    onColorChanged: (Int) -> Unit,
    onFrequencyChanged: (HabitFrequencyType) -> Unit,
    onFrequencyValueChanged: (String) -> Unit,
    onCustomDayToggled: (DayOfWeek) -> Unit,
    onStartDateChanged: (LocalDate) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (editor.editingId == null) "新建习惯" else "编辑习惯") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(editor.name, onNameChanged, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), isError = editor.validationMessage != null) }
                item { OutlinedTextField(editor.iconKey, onIconChanged, label = { Text("图标名称") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFF00695C.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt()).forEach { color ->
                            FilterChip(selected = editor.color == color, onClick = { onColorChanged(color) }, label = { Text("颜色") })
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HabitFrequencyType.entries) { type ->
                            FilterChip(selected = editor.frequencyType == type, onClick = { onFrequencyChanged(type) }, label = { Text(frequencyLabel(type, 1)) })
                        }
                    }
                }
                if (editor.frequencyType == HabitFrequencyType.WEEKLY || editor.frequencyType == HabitFrequencyType.MONTHLY) {
                    item { OutlinedTextField(editor.frequencyValue, onFrequencyValueChanged, label = { Text("目标次数") }, modifier = Modifier.fillMaxWidth()) }
                }
                if (editor.frequencyType == HabitFrequencyType.CUSTOM) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(selected = day in editor.customDaysOfWeek, onClick = { onCustomDayToggled(day) }, label = { Text(day.value.toString()) })
                            }
                        }
                    }
                }
                item { OutlinedButton(onClick = { showDatePicker = true }) { Text("开始日期：${editor.startDate}") } }
                item { OutlinedTextField(editor.note, onNoteChanged, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth()) }
                editor.validationMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { Button(onClick = onSave, enabled = !editor.isSaving) { Text("保存") } },
        dismissButton = {
            Row {
                if (editor.editingId != null) TextButton(onClick = { onDelete(editor.editingId) }) { Text("删除") }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = editor.startDate.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onStartDateChanged(LocalDate.ofEpochDay(it / 86_400_000L)) }
                    showDatePicker = false
                }) { Text("确定") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

private fun frequencyLabel(type: HabitFrequencyType, value: Int): String = when (type) {
    HabitFrequencyType.DAILY -> "每天"
    HabitFrequencyType.WEEKLY -> "每周 ${value} 次"
    HabitFrequencyType.MONTHLY -> "每月 ${value} 次"
    HabitFrequencyType.CUSTOM -> "自定义星期"
}
