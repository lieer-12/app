package com.example.lifemanager.ui.todo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoDateFilter
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import com.example.lifemanager.ui.settings.displayDate
import com.example.lifemanager.ui.settings.displayDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    initialTodoId: Long? = null,
    notificationToken: Long? = null,
    onNotificationConsumed: (Long) -> Unit = {},
    onOpenSettings: () -> Unit,
    viewModel: TodoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val renderedGeneration = state.generation
    val editorGeneration = state.editor.generation
    val controlsEnabled = state.isAvailable && !state.isMaintaining
    val renderedFilter = state.filter
    var showSearch by remember { mutableStateOf(false) }
    LaunchedEffect(initialTodoId, notificationToken) {
        initialTodoId?.let(viewModel::openNotificationDetail)
        notificationToken?.let(onNotificationConsumed)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待办事项") },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.semantics { if (!controlsEnabled) disabled() },
                onClick = { if (controlsEnabled) viewModel.openEditor(generation = renderedGeneration) },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加待办")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (showSearch) {
                OutlinedTextField(
                    value = state.filter.query,
                    onValueChange = { viewModel.onFilterChanged(renderedFilter.copy(query = it), renderedGeneration) },
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("搜索标题或备注") },
                    singleLine = true,
                )
            }
            StatsHeader(state)
            DateFilters(state.filter, controlsEnabled) { viewModel.onFilterChanged(it, renderedGeneration) }
            PriorityFilters(state.filter, controlsEnabled) { viewModel.onFilterChanged(it, renderedGeneration) }
            if (state.tags.isNotEmpty()) {
                TagFilters(state, controlsEnabled) { viewModel.onFilterChanged(it, renderedGeneration) }
            }
            Spacer(Modifier.height(8.dp))
            if (state.isMaintaining) Text("正在维护数据，请稍后重试")
            state.errorMessage?.let { error ->
                Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            if (state.isLoading) {
                Text("加载中…", modifier = Modifier.padding(16.dp))
            } else if (state.todos.isEmpty()) {
                EmptyTodoState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.todos, key = Todo::id) { todo ->
                        TodoRow(
                            todo = todo,
                            enabled = controlsEnabled,
                            onToggle = { viewModel.toggleTodo(todo, renderedGeneration) },
                            onDelete = { viewModel.deleteTodo(todo.id, renderedGeneration) },
                            onEdit = { viewModel.openEditor(todo, renderedGeneration) },
                        )
                    }
                }
            }
        }
    }

    if (state.editor.isOpen) {
        key(editorGeneration) {
            TodoEditorDialog(
                state = state.editor,
                enabled = controlsEnabled,
                onDismiss = { viewModel.closeEditor(editorGeneration) },
                onTitleChanged = { viewModel.onTitleChanged(it, editorGeneration) },
                onDescriptionChanged = { viewModel.onDescriptionChanged(it, editorGeneration) },
                onPriorityChanged = { viewModel.onPriorityChanged(it, editorGeneration) },
                onDueAtChanged = { viewModel.onDueAtChanged(it, editorGeneration) },
                onTagInputChanged = { viewModel.onTagInputChanged(it, editorGeneration) },
                onSave = { viewModel.saveTodo(editorGeneration) },
            )
        }
    }
}

@Composable
private fun StatsHeader(state: TodoUiState) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text("今日进度", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text("已完成 ${state.stats.completedCount} 项 · 待完成 ${state.stats.pendingCount} 项 · ${(state.stats.completionRate * 100).toInt()}%")
    }
}

@Composable
private fun DateFilters(filter: TodoFilter, enabled: Boolean, onChanged: (TodoFilter) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            TodoDateFilter.TODAY to "今天",
            TodoDateFilter.TOMORROW to "明天",
            TodoDateFilter.THIS_WEEK to "本周",
            TodoDateFilter.ALL to "全部",
        ).forEach { (dateFilter, label) ->
            FilterChip(
                selected = filter.dateFilter == dateFilter,
                enabled = enabled,
                onClick = { onChanged(filter.copy(dateFilter = dateFilter)) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun PriorityFilters(filter: TodoFilter, enabled: Boolean, onChanged: (TodoFilter) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.priority == null,
            enabled = enabled,
            onClick = { onChanged(filter.copy(priority = null)) },
            label = { Text("全部优先级") },
        )
        TodoPriority.values().filter { it != TodoPriority.NONE }.forEach { priority ->
            FilterChip(
                selected = filter.priority == priority,
                enabled = enabled,
                onClick = { onChanged(filter.copy(priority = priority)) },
                label = { Text(priority.label()) },
            )
        }
    }
}

@Composable
private fun TagFilters(state: TodoUiState, enabled: Boolean, onChanged: (TodoFilter) -> Unit) {
    val filter = state.filter
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.filter.tagId == null,
            onClick = { onChanged(filter.copy(tagId = null)) },
            enabled = enabled,
            label = { Text("全部标签") },
        )
        state.tags.forEach { tag ->
            FilterChip(
                selected = state.filter.tagId == tag.id,
                onClick = { onChanged(filter.copy(tagId = tag.id)) },
                enabled = enabled,
                label = { Text(tag.name) },
            )
        }
    }
}

@Composable
private fun TodoRow(todo: Todo, enabled: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onEdit)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = todo.isCompleted, onCheckedChange = { onToggle() }, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(todo.title)
                val metadata = buildList {
                    if (todo.priority != TodoPriority.NONE) add(todo.priority.label())
                    if (todo.dueAt != null) add(formatDate(todo.dueAt))
                    if (todo.tagNames.isNotEmpty()) add(todo.tagNames.joinToString(" · "))
                }
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun EmptyTodoState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("还没有待办，点击右下角开始记录")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditorDialog(
    state: TodoEditorState,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPriorityChanged: (TodoPriority) -> Unit,
    onDueAtChanged: (Instant?) -> Unit,
    onTagInputChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    val editingEnabled = enabled && !state.isSaving
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.editingId == null) "新建待办" else "编辑待办") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.title,
                    enabled = editingEnabled,
                    onValueChange = onTitleChanged,
                    label = { Text("标题") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = state.description,
                    enabled = editingEnabled,
                    onValueChange = onDescriptionChanged,
                    label = { Text("备注") },
                )
                Text("优先级")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TodoPriority.values().forEach { priority ->
                        FilterChip(
                            selected = state.priority == priority,
                            enabled = editingEnabled,
                            onClick = { onPriorityChanged(priority) },
                            label = { Text(priority.label()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.tagInput,
                    enabled = editingEnabled,
                    onValueChange = onTagInputChanged,
                    label = { Text("标签（用逗号分隔）") },
                    supportingText = { Text("保存后会转换为独立标签记录") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.dueAt?.let { displayDateTime(it) } ?: "未设置截止日期", modifier = Modifier.weight(1f))
                    TextButton(onClick = { showDatePicker = true }, enabled = editingEnabled) { Text("日期") }
                    TextButton(onClick = { showTimePicker = true }, enabled = editingEnabled) { Text("时间") }
                    if (state.dueAt != null) TextButton(onClick = { onDueAtChanged(null) }, enabled = editingEnabled) { Text("清除") }
                }
                state.validationMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                if (state.pendingNotificationId != null) Text("通知待办等待查看；保存或关闭当前草稿后打开")
            }
        },
        confirmButton = { Button(onClick = onSave, enabled = editingEnabled) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = editingEnabled) { Text("取消") } },
    )

    if (showDatePicker) {
        // Material DatePicker encodes a date at UTC midnight, not an arbitrary local instant.
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.dueAt
            ?.atZone(ZoneId.systemDefault())?.toLocalDate()?.atStartOfDay(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(enabled = editingEnabled, onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        val time = state.dueAt?.atZone(ZoneId.systemDefault())?.toLocalTime() ?: LocalTime.of(18, 0)
                        onDueAtChanged(date.atTime(time).atZone(ZoneId.systemDefault()).toInstant())
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }

    if (showTimePicker) {
        val currentTime = state.dueAt?.atZone(ZoneId.systemDefault())?.toLocalTime() ?: LocalTime.of(18, 0)
        val pickerState = rememberTimePickerState(
            initialHour = currentTime.hour,
            initialMinute = currentTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(enabled = editingEnabled, onClick = {
                    val date = state.dueAt?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: java.time.LocalDate.now()
                    onDueAtChanged(date.atTime(pickerState.hour, pickerState.minute).atZone(ZoneId.systemDefault()).toInstant())
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
            text = { TimePicker(state = pickerState) },
        )
    }
}

private fun TodoPriority.label(): String = when (this) {
    TodoPriority.NONE -> "无"
    TodoPriority.LOW -> "低"
    TodoPriority.MEDIUM -> "中"
    TodoPriority.HIGH -> "高"
}

@Composable private fun formatDate(value: Instant): String = displayDate(value)
