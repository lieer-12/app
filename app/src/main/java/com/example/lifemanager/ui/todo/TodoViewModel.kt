package com.example.lifemanager.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.usecase.DeleteTodoUseCase
import com.example.lifemanager.domain.usecase.ObserveTodosUseCase
import com.example.lifemanager.domain.usecase.SaveTodoUseCase
import com.example.lifemanager.domain.usecase.TodoRules
import com.example.lifemanager.domain.usecase.ToggleTodoUseCase
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.notification.ReminderSchedulerContract
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val observeTodos: ObserveTodosUseCase,
    private val saveTodo: SaveTodoUseCase,
    private val deleteTodo: DeleteTodoUseCase,
    private val toggleTodo: ToggleTodoUseCase,
    private val repository: TodoRepository,
    private val reminderScheduler: ReminderSchedulerContract,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    constructor(
        repository: TodoRepository,
        reminderScheduler: ReminderSchedulerContract,
        dispatcher: CoroutineDispatcher,
    ) : this(
        observeTodos = ObserveTodosUseCase(repository),
        saveTodo = SaveTodoUseCase(repository),
        deleteTodo = DeleteTodoUseCase(repository),
        toggleTodo = ToggleTodoUseCase(repository),
        repository = repository,
        reminderScheduler = reminderScheduler,
        dispatcher = dispatcher,
    )
    private val filter = MutableStateFlow(TodoFilter())
    private val editor = MutableStateFlow(TodoEditorState())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val todos = filter.flatMapLatest(observeTodos::invoke).catch { error ->
        errorMessage.value = "读取待办失败：${error.message ?: "未知错误"}"
        emit(emptyList())
    }
    private val allTodos = observeTodos(TodoFilter(dateFilter = com.example.lifemanager.domain.model.TodoDateFilter.ALL))
        .catch { error ->
            errorMessage.value = "读取统计失败：${error.message ?: "未知错误"}"
            emit(emptyList())
        }
    private val tags = repository.observeTags().catch { error ->
        errorMessage.value = "读取标签失败：${error.message ?: "未知错误"}"
        emit(emptyList())
    }

    private val contentState = combine(
        todos,
        tags,
        allTodos,
        filter,
        editor,
    ) { todoList, tagList, allTodoList, currentFilter, currentEditor ->
        TodoUiState(
            todos = todoList,
            tags = tagList,
            filter = currentFilter,
            stats = TodoRules.calculateStats(allTodoList, java.time.LocalDate.now()),
            editor = currentEditor,
            isLoading = false,
        )
    }

    val uiState: StateFlow<TodoUiState> = combine(contentState, errorMessage) { state, error ->
        state.copy(errorMessage = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState())

    fun onFilterChanged(newFilter: TodoFilter) {
        filter.value = newFilter
    }

    fun openEditor(todo: Todo? = null) {
        editor.value = TodoEditorState(
            isOpen = true,
            editingId = todo?.id,
            title = todo?.title.orEmpty(),
            description = todo?.description.orEmpty(),
            priority = todo?.priority ?: com.example.lifemanager.domain.model.TodoPriority.NONE,
            dueAt = todo?.dueAt,
            tagInput = todo?.tagNames?.joinToString(", ").orEmpty(),
        )
    }

    fun closeEditor() {
        editor.value = TodoEditorState()
    }

    fun onTitleChanged(value: String) = editor.update { it.copy(title = value, validationMessage = null) }
    fun onDescriptionChanged(value: String) = editor.update { it.copy(description = value) }
    fun onPriorityChanged(value: com.example.lifemanager.domain.model.TodoPriority) = editor.update { it.copy(priority = value) }
    fun onDueAtChanged(value: Instant?) = editor.update { it.copy(dueAt = value) }
    fun onTagInputChanged(value: String) = editor.update { it.copy(tagInput = value) }

    fun saveTodo() {
        errorMessage.value = null
        val current = editor.value
        val validation = TodoRules.validateTitle(current.title)
        if (validation != null) {
            editor.update { it.copy(validationMessage = validation) }
            return
        }
        editor.update { it.copy(isSaving = true) }
        viewModelScope.launch(dispatcher) {
            val now = Instant.now()
            val existing = uiState.value.todos.firstOrNull { it.id == current.editingId }
            val todo = Todo(
                id = current.editingId ?: 0L,
                title = current.title.trim(),
                description = current.description.trim().ifEmpty { null },
                priority = current.priority,
                dueAt = current.dueAt,
                isCompleted = existing?.isCompleted ?: false,
                completedAt = existing?.completedAt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                tagNames = current.tagInput.split(",").map(String::trim).filter(String::isNotEmpty),
            )
            try {
                val id = saveTodo(todo, todo.tagNames)
                if (todo.isCompleted || todo.dueAt == null) {
                    reminderScheduler.cancel(id)
                } else {
                    reminderScheduler.schedule(id, todo.title, todo.dueAt)
                }
                editor.value = TodoEditorState()
            } catch (error: Exception) {
                errorMessage.value = "保存失败：${error.message ?: "未知错误"}"
                editor.update { it.copy(isSaving = false) }
            }
        }
    }

    fun toggleTodo(todo: Todo) {
        viewModelScope.launch(dispatcher) {
            try {
                val completed = !todo.isCompleted
                toggleTodo(todo.id, completed)
                if (completed || todo.dueAt == null) reminderScheduler.cancel(todo.id)
                else reminderScheduler.schedule(todo.id, todo.title, todo.dueAt)
            } catch (error: Exception) {
                errorMessage.value = "更新待办失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun deleteTodo(todoId: Long) {
        viewModelScope.launch(dispatcher) {
            try {
                deleteTodo(todoId)
                reminderScheduler.cancel(todoId)
            } catch (error: Exception) {
                errorMessage.value = "删除失败：${error.message ?: "未知错误"}"
            }
        }
    }
}

private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
