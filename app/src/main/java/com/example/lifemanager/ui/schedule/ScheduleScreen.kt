package com.example.lifemanager.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleOccurrence
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    initialScheduleId: Long? = null,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var initialHandled by remember(initialScheduleId) { mutableStateOf(false) }
    LaunchedEffect(initialScheduleId, state.schedules) {
        if (!initialHandled) {
            state.schedules.firstOrNull { it.id == initialScheduleId }?.let {
                viewModel.openEditor(it)
                initialHandled = true
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("日程") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openEditor() }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加日程")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CalendarToolbar(state, viewModel)
            state.errorMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            if (state.isLoading) Text("加载中…") else when (state.viewMode) {
                CalendarViewMode.MONTH -> MonthCalendar(state, viewModel)
                CalendarViewMode.WEEK, CalendarViewMode.DAY -> OccurrenceList(state, viewModel)
            }
        }
    }
    if (state.editor.isOpen) {
        ScheduleEditorDialog(
            state = state.editor,
            onDismiss = viewModel::closeEditor,
            onTitleChanged = viewModel::onTitleChanged,
            onAllDayChanged = viewModel::onAllDayChanged,
            onStartChanged = viewModel::onStartChanged,
            onEndChanged = viewModel::onEndChanged,
            onAllDayDatesChanged = viewModel::onAllDayDatesChanged,
            onLocationChanged = viewModel::onLocationChanged,
            onParticipantsChanged = viewModel::onParticipantsChanged,
            onNoteChanged = viewModel::onNoteChanged,
            onColorChanged = viewModel::onColorChanged,
            onReminderChanged = viewModel::onReminderChanged,
            onRepeatRuleChanged = viewModel::onRepeatRuleChanged,
            onSave = viewModel::saveSchedule,
            onConfirmConflict = viewModel::confirmSaveDespiteConflicts,
            onDelete = viewModel::deleteSchedule,
        )
    }
}

@Composable
private fun CalendarToolbar(state: ScheduleUiState, viewModel: ScheduleViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = viewModel::previousPeriod) { Icon(Icons.Outlined.ChevronLeft, "上一段") }
        Text(periodTitle(state), modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        IconButton(onClick = viewModel::nextPeriod) { Icon(Icons.Outlined.ChevronRight, "下一段") }
    }
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CalendarViewMode.entries.forEach { mode ->
            FilterChip(selected = state.viewMode == mode, onClick = { viewModel.selectViewMode(mode) }, label = { Text(mode.label()) })
        }
    }
}

@Composable
private fun MonthCalendar(state: ScheduleUiState, viewModel: ScheduleViewModel) {
    val month = YearMonth.from(state.selectedDate)
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
            Text(label, modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        }
    }
    val prefix = month.atDay(1).dayOfWeek.value - 1
    val cells = List(prefix) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    cells.chunked(7).forEach { week ->
        Row(modifier = Modifier.fillMaxWidth()) {
            (week + List(7 - week.size) { null }).forEach { date ->
                Box(
                    modifier = Modifier.weight(1f).height(54.dp).padding(2.dp)
                        .background(if (date == state.selectedDate) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable(enabled = date != null) { date?.let { viewModel.selectDate(it, switchToDay = true) } },
                ) {
                    if (date != null) {
                        Column(modifier = Modifier.padding(5.dp)) {
                            Text(date.dayOfMonth.toString())
                            state.occurrences.filter { it.occursOn(date) }.take(2).forEach {
                                Box(Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp).background(Color(it.color)))
                            }
                        }
                    }
                }
            }
        }
    }
    Text("本月日程", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
    OccurrenceCards(state.occurrences, viewModel)
}

@Composable
private fun OccurrenceList(state: ScheduleUiState, viewModel: ScheduleViewModel) {
    if (state.occurrences.isEmpty()) {
        Text("这段时间没有日程，点击右下角添加")
    } else {
        OccurrenceCards(state.occurrences, viewModel)
    }
}

@Composable
private fun OccurrenceCards(occurrences: List<ScheduleOccurrence>, viewModel: ScheduleViewModel) {
    occurrences.forEach { occurrence ->
        Card(modifier = Modifier.fillMaxWidth().clickable { viewModel.openEditor(occurrence.source) }) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(occurrence.color)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(occurrence.title)
                    Text(occurrence.displayTime(), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    occurrence.source.location?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditorDialog(
    state: ScheduleEditorState,
    onDismiss: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onAllDayChanged: (Boolean) -> Unit,
    onStartChanged: (Instant) -> Unit,
    onEndChanged: (Instant) -> Unit,
    onAllDayDatesChanged: (LocalDate, LocalDate) -> Unit,
    onLocationChanged: (String) -> Unit,
    onParticipantsChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onColorChanged: (Int) -> Unit,
    onReminderChanged: (String) -> Unit,
    onRepeatRuleChanged: (ScheduleRepeatRule) -> Unit,
    onSave: () -> Unit,
    onConfirmConflict: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.editingId == null) "新建日程" else "编辑日程") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = state.title, onValueChange = onTitleChanged, label = { Text("标题") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.isAllDay, onCheckedChange = onAllDayChanged)
                    Text("全天事件")
                }
                if (state.isAllDay) {
                    DateLine("开始日期", state.allDayStartDate, { pickerTarget = PickerTarget.ALL_DAY_START })
                    DateLine("结束日期", state.allDayEndDate, { pickerTarget = PickerTarget.ALL_DAY_END })
                } else {
                    DateLine("开始日期", state.startAt?.atZone(ZoneId.systemDefault())?.toLocalDate(), { pickerTarget = PickerTarget.START_DATE })
                    DateLine("结束日期", state.endAt?.atZone(ZoneId.systemDefault())?.toLocalDate(), { pickerTarget = PickerTarget.END_DATE })
                    TimeLine("开始时间", state.startAt, { pickerTarget = PickerTarget.START_TIME })
                    TimeLine("结束时间", state.endAt, { pickerTarget = PickerTarget.END_TIME })
                }
                OutlinedTextField(value = state.location, onValueChange = onLocationChanged, label = { Text("地点") })
                OutlinedTextField(value = state.participants, onValueChange = onParticipantsChanged, label = { Text("参与者（文本）") })
                OutlinedTextField(value = state.note, onValueChange = onNoteChanged, label = { Text("备注") })
                OutlinedTextField(value = state.reminderMinutes, onValueChange = onReminderChanged, label = { Text("提前提醒分钟（留空关闭）") }, singleLine = true)
                Text("重复")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleRepeatRule.entries.forEach { rule ->
                        FilterChip(selected = state.repeatRule == rule, onClick = { onRepeatRuleChanged(rule) }, label = { Text(rule.label()) })
                    }
                }
                Text("颜色")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFF00695C.toInt(), 0xFF3F51B5.toInt(), 0xFFC62828.toInt(), 0xFFEF6C00.toInt()).forEach { color ->
                        AssistChip(onClick = { onColorChanged(color) }, label = { Text(if (state.color == color) "已选" else "颜色") }, leadingIcon = { Box(Modifier.size(10.dp).background(Color(color))) })
                    }
                }
                state.validationMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = if (state.awaitingConflictConfirmation) onConfirmConflict else onSave, enabled = !state.isSaving) {
                Text(if (state.awaitingConflictConfirmation) "仍然保存" else "保存")
            }
        },
        dismissButton = {
            Row {
                if (state.editingId != null) TextButton(onClick = { onDelete(state.editingId) }) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
    when (pickerTarget) {
        PickerTarget.START_DATE, PickerTarget.END_DATE, PickerTarget.ALL_DAY_START, PickerTarget.ALL_DAY_END -> {
            val currentDate = when (pickerTarget) {
                PickerTarget.START_DATE -> state.startAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
                PickerTarget.END_DATE -> state.endAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
                PickerTarget.ALL_DAY_START -> state.allDayStartDate
                PickerTarget.ALL_DAY_END -> state.allDayEndDate
                else -> null
            } ?: LocalDate.now()
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = currentDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest = { pickerTarget = null }, confirmButton = {
                TextButton(onClick = {
                    val date = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() } ?: currentDate
                    when (pickerTarget) {
                        PickerTarget.START_DATE -> onStartChanged(date.atTime(state.startAt?.atZone(ZoneId.systemDefault())?.toLocalTime() ?: LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant())
                        PickerTarget.END_DATE -> onEndChanged(date.atTime(state.endAt?.atZone(ZoneId.systemDefault())?.toLocalTime() ?: LocalTime.of(10, 0)).atZone(ZoneId.systemDefault()).toInstant())
                        PickerTarget.ALL_DAY_START -> onAllDayDatesChanged(date, state.allDayEndDate ?: date)
                        PickerTarget.ALL_DAY_END -> onAllDayDatesChanged(state.allDayStartDate ?: date, date)
                        else -> Unit
                    }
                    pickerTarget = null
                }) { Text("确定") }
            }, dismissButton = { TextButton(onClick = { pickerTarget = null }) { Text("取消") } }) { DatePicker(state = pickerState) }
        }
        PickerTarget.START_TIME, PickerTarget.END_TIME -> {
            val current = if (pickerTarget == PickerTarget.START_TIME) state.startAt else state.endAt
            val localTime = current?.atZone(ZoneId.systemDefault())?.toLocalTime() ?: LocalTime.of(9, 0)
            val pickerState = rememberTimePickerState(localTime.hour, localTime.minute, is24Hour = true)
            AlertDialog(onDismissRequest = { pickerTarget = null }, confirmButton = {
                TextButton(onClick = {
                    val date = current?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: LocalDate.now()
                    val value = date.atTime(pickerState.hour, pickerState.minute).atZone(ZoneId.systemDefault()).toInstant()
                    if (pickerTarget == PickerTarget.START_TIME) onStartChanged(value) else onEndChanged(value)
                    pickerTarget = null
                }) { Text("确定") }
            }, dismissButton = { TextButton(onClick = { pickerTarget = null }) { Text("取消") } }, text = { TimePicker(state = pickerState) })
        }
        null -> Unit
    }
}

@Composable private fun DateLine(label: String, date: LocalDate?, onClick: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Text("$label：${date ?: "未设置"}", modifier = Modifier.weight(1f)); TextButton(onClick = onClick) { Text("选择") } }
@Composable private fun TimeLine(label: String, time: Instant?, onClick: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Text("$label：${time?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "未设置"}", modifier = Modifier.weight(1f)); TextButton(onClick = onClick) { Text("选择") } }
private fun periodTitle(state: ScheduleUiState): String = when (state.viewMode) {
    CalendarViewMode.MONTH -> YearMonth.from(state.selectedDate).format(DateTimeFormatter.ofPattern("yyyy年M月"))
    CalendarViewMode.WEEK -> {
        val start = state.selectedDate.minusDays((state.selectedDate.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(6)
        "${start.format(DateTimeFormatter.ofPattern("M月d日"))} - ${end.format(DateTimeFormatter.ofPattern("M月d日"))}"
    }
    CalendarViewMode.DAY -> state.selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
}
private fun CalendarViewMode.label() = when (this) { CalendarViewMode.MONTH -> "月"; CalendarViewMode.WEEK -> "周"; CalendarViewMode.DAY -> "日" }
private fun ScheduleRepeatRule.label() = when (this) { ScheduleRepeatRule.NONE -> "不重复"; ScheduleRepeatRule.DAILY -> "每天"; ScheduleRepeatRule.WEEKLY -> "每周"; ScheduleRepeatRule.MONTHLY -> "每月"; ScheduleRepeatRule.YEARLY -> "每年"; ScheduleRepeatRule.CUSTOM -> "自定义" }
private fun ScheduleOccurrence.occursOn(date: LocalDate): Boolean = (allDayStartDate ?: startAt!!.atZone(ZoneId.systemDefault()).toLocalDate()) <= date && (allDayEndDate ?: endAt!!.minusMillis(1).atZone(ZoneId.systemDefault()).toLocalDate()) >= date
private fun ScheduleOccurrence.displayTime(): String = if (isAllDay) "全天" else "${startAt!!.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} - ${endAt!!.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}"
private enum class PickerTarget { START_DATE, END_DATE, ALL_DAY_START, ALL_DAY_END, START_TIME, END_TIME }
