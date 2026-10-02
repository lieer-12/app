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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModel @Inject constructor(
    private val observeTodos: ObserveTodosUseCase,
    private val saveTodo: SaveTodoUseCase,
    private val deleteTodoUseCase: DeleteTodoUseCase,
    private val toggleTodo: ToggleTodoUseCase,
    private val repository: TodoRepository,
    private val reminderScheduler: ReminderSchedulerContract,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    constructor(
        repository: TodoRepository,
        reminderScheduler: ReminderSchedulerContract,
        dispatcher: CoroutineDispatcher,
    ) : this(
        observeTodos = ObserveTodosUseCase(repository),
        saveTodo = SaveTodoUseCase(repository),
        deleteTodoUseCase = DeleteTodoUseCase(repository),
        toggleTodo = ToggleTodoUseCase(repository),
        repository = repository,
        reminderScheduler = reminderScheduler,
        dispatcher = dispatcher,
    )
    private val filter = MutableStateFlow(TodoFilter())
    private val editor = MutableStateFlow(TodoEditorState())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val notificationGeneration = AtomicLong()
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TodoUiState())

    fun onFilterChanged(newFilter: TodoFilter) {
        filter.value = newFilter
    }

    fun openEditor(todo: Todo? = null) {
        if (editor.value.isSaving) return
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
        if (editor.value.isSaving) return
        val previous = editor.getAndUpdate { TodoEditorState() }
        drainNotification(previous)
    }

    fun openNotificationDetail(id: Long) {
        if (id <= 0L) return
        val request = notificationGeneration.incrementAndGet()
        val previous = editor.getAndUpdate {
            if (it.isOpen) it.copy(pendingNotificationId = id, pendingNotificationToken = request) else it
        }
        if (previous.isOpen) return
        loadNotificationDetail(id, request)
    }

    private fun drainNotification(previous: TodoEditorState) {
        val id = previous.pendingNotificationId ?: return
        val token = previous.pendingNotificationToken ?: return
        // Keep its original order: draining an old deferred request is not a new tap.
        loadNotificationDetail(id, token)
    }

    private fun loadNotificationDetail(id: Long, request: Long) {
        if (request != notificationGeneration.get()) return
        viewModelScope.launch {
            try {
                val target = withContext(dispatcher) { repository.getAllTodos().firstOrNull { it.id == id } }
                if (request != notificationGeneration.get()) return@launch
                // Atomic handoff: never attach a pending target to an already closed editor.
                val previous = editor.getAndUpdate {
                    if (it.isOpen) it.copy(pendingNotificationId = id, pendingNotificationToken = request) else it
                }
                if (previous.isOpen) return@launch
                if (target == null) errorMessage.value = "通知对应的待办已删除或不存在"
                else openEditor(target)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (request == notificationGeneration.get())
                    errorMessage.value = "读取通知待办失败：${error.message ?: "未知错误"}"
            }
        }
    }

    private fun edit(transform: (TodoEditorState) -> TodoEditorState) {
        editor.update { if (it.isSaving) it else transform(it) }
    }
    fun onTitleChanged(value: String) = edit { it.copy(title = value, validationMessage = null) }
    fun onDescriptionChanged(value: String) = edit { it.copy(description = value) }
    fun onPriorityChanged(value: com.example.lifemanager.domain.model.TodoPriority) = edit { it.copy(priority = value) }
    fun onDueAtChanged(value: Instant?) = edit { it.copy(dueAt = value) }
    fun onTagInputChanged(value: String) = edit { it.copy(tagInput = value) }

    fun saveTodo() {
        if (!editor.value.isOpen || editor.value.isSaving) return
        errorMessage.value = null
        val current = editor.value
        val validation = TodoRules.validateTitle(current.title)
        if (validation != null) {
            editor.update { it.copy(validationMessage = validation) }
            return
        }
        editor.update { it.copy(isSaving = true) }
        viewModelScope.launch(dispatcher) {
            try {
                TodoOperationCoordinator.run {
                    val existing = current.editingId?.let { id ->
                        requireNotNull(repository.getAllTodos().firstOrNull { it.id == id }) { "待办已删除，请关闭旧表单" }
                    }
                    val now = Instant.now()
                    val todo = (existing ?: Todo(title = current.title, createdAt = now)).copy(
                        title = current.title.trim(), description = current.description.trim().ifEmpty { null },
                        priority = current.priority, dueAt = current.dueAt, updatedAt = now,
                        tagNames = current.tagInput.split(",").map(String::trim).filter(String::isNotEmpty),
                    )
                    val id = saveTodo(todo, todo.tagNames)
                    // A successful Room write must never remain a retryable unsaved insert.
                    // Serialize the commit handoff with UI/notification editor changes on Main.
                    val previous = withContext(Dispatchers.Main.immediate) {
                        editor.getAndUpdate { TodoEditorState() }
                    }
                    try { reconcileReminder(todo.copy(id = id)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errorMessage.value = "待办已保存，但提醒设置失败：${error.message ?: "未知错误"}" }
                    drainNotification(previous)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    errorMessage.value = "保存失败：${error.message ?: "未知错误"}"
                    editor.update { it.copy(isSaving = false, validationMessage = errorMessage.value) }
                }
            }
        }
    }

    fun toggleTodo(todo: Todo) {
        viewModelScope.launch(dispatcher) {
            try {
                TodoOperationCoordinator.run {
                    val current = requireNotNull(repository.getAllTodos().firstOrNull { it.id == todo.id }) { "待办已删除" }
                    val completed = !current.isCompleted
                    toggleTodo(todo.id, completed)
                    try { reconcileReminder(current.copy(isCompleted = completed)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errorMessage.value = "待办状态已更新，但提醒设置失败：${error.message ?: "未知错误"}" }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                errorMessage.value = "更新待办失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun deleteTodo(todoId: Long) {
        viewModelScope.launch(dispatcher) {
            try {
                TodoOperationCoordinator.run {
                    deleteTodoUseCase(todoId)
                    try { reminderScheduler.cancel(todoId) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errorMessage.value = "待办已删除，但提醒清理失败：${error.message ?: "未知错误"}" }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                errorMessage.value = "删除失败：${error.message ?: "未知错误"}"
            }
        }
    }

    private fun reconcileReminder(todo: Todo) {
        val dueAt = todo.dueAt
        if (todo.isCompleted || dueAt == null) reminderScheduler.cancel(todo.id)
        else reminderScheduler.schedule(todo.id, todo.title, dueAt)
    }
}
