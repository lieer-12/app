package com.example.lifemanager.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoDateFilter
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.usecase.DeleteTodoUseCase
import com.example.lifemanager.domain.usecase.ObserveTodosUseCase
import com.example.lifemanager.domain.usecase.SaveTodoUseCase
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import com.example.lifemanager.domain.usecase.TodoRules
import com.example.lifemanager.domain.usecase.ToggleTodoUseCase
import com.example.lifemanager.notification.ReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val access: GenerationAccess,
) : ViewModel() {
    constructor(
        repository: TodoRepository,
        reminderScheduler: ReminderSchedulerContract,
        dispatcher: CoroutineDispatcher,
        access: GenerationAccess,
    ) : this(
        observeTodos = ObserveTodosUseCase(repository),
        saveTodo = SaveTodoUseCase(repository),
        deleteTodoUseCase = DeleteTodoUseCase(repository),
        toggleTodo = ToggleTodoUseCase(repository),
        repository = repository,
        reminderScheduler = reminderScheduler,
        dispatcher = dispatcher,
        access = access,
    )

    private val state = MutableStateFlow(TodoUiState())
    val uiState = state.asStateFlow()
    private val filter = MutableStateFlow(TodoFilter())
    private val notificationSequence = AtomicLong()
    private var notificationGeneration: DataGeneration? = null
    // These labels and snapshots are owned by Main, including the error fallback checks.
    private var observedGeneration: DataGeneration? = null
    private var allSnapshot = emptyList<Todo>()
    private var activeSaveOperation: Any? = null

    init {
        viewModelScope.launch(dispatcher) {
            var contextGeneration: DataGeneration? = null
            try {
                combine(access.generations, access.maintenance, filter) { generation, phase, selected ->
                    ReadContext(generation, phase, selected)
                }.collectLatest { context ->
                    contextGeneration = context.generation
                    withContext(Dispatchers.Main.immediate) {
                        observeGeneration(context.generation)
                        state.update { it.copy(isMaintaining = context.phase != MaintenanceState.IDLE) }
                    }
                    if (context.phase != MaintenanceState.IDLE) return@collectLatest
                    try {
                        // Long streams only invalidate. Fresh finite queries and Main publication
                        // share one permit; no buffered DTO is stamped with a newer generation.
                        merge(
                            observeTodos(context.filter).map { Unit },
                            observeTodos(TodoFilter(dateFilter = TodoDateFilter.ALL)).map { Unit },
                            repository.observeTags().map { Unit },
                        ).collect {
                            access.read({
                                Snapshot(
                                    observeTodos(context.filter).first(),
                                    observeTodos(TodoFilter(dateFilter = TodoDateFilter.ALL)).first(),
                                    repository.observeTags().first(),
                                )
                            }) { token, fresh ->
                                observeGeneration(token)
                                allSnapshot = fresh.all
                                state.update { it.copy(
                                    generation = token,
                                    todos = fresh.visible,
                                    tags = fresh.tags,
                                    filter = context.filter,
                                    stats = TodoRules.calculateStats(fresh.all, LocalDate.now()),
                                    isAvailable = true,
                                    isLoading = false,
                                    errorMessage = if (it.isAvailable) it.errorMessage else null,
                                ) }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        reportFailure(context.generation, "读取待办或标签失败", error, unavailable = true)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val failed = contextGeneration
                if (failed != null) reportFailure(failed, "读取数据世代失败", error, unavailable = true)
                else withContext(Dispatchers.Main.immediate) {
                    if (observedGeneration == null && state.value.generation == null) metadataFailed()
                }
            }
        }
    }

    private fun observeGeneration(token: DataGeneration) {
        val previous = observedGeneration ?: state.value.generation ?: state.value.editor.generation
        // A context queued before a newer admitted Main publication cannot roll it back.
        if (previous != null && token.value < previous.value) return
        if (previous != null && previous != token) {
            // An intermediate observation may invalidate the page, not a newer tagged request.
            if (notificationGeneration?.let { it.value < token.value } != false) {
                notificationSequence.incrementAndGet()
                notificationGeneration = null
            }
            allSnapshot = emptyList()
            activeSaveOperation = null
            state.update { TodoUiState(filter = it.filter, isMaintaining = it.isMaintaining) }
        }
        observedGeneration = token
    }

    fun onFilterChanged(newFilter: TodoFilter, generation: DataGeneration? = state.value.generation) {
        if (generation != state.value.generation || access.maintenance.value != MaintenanceState.IDLE) return
        state.update { it.copy(filter = newFilter) }
        filter.value = newFilter
    }

    fun openEditor(todo: Todo? = null, generation: DataGeneration? = state.value.generation) {
        if (!state.value.isAvailable || state.value.editor.isSaving || generation != state.value.generation) return
        val token = eventToken(generation) ?: return
        val current = todo?.let { target -> allSnapshot.firstOrNull { it.id == target.id } ?: return }
        state.update { it.copy(editor = editorFor(current, token)) }
    }

    private fun editorFor(todo: Todo?, token: DataGeneration) = TodoEditorState(
        generation = token,
        isOpen = true,
        editingId = todo?.id,
        title = todo?.title.orEmpty(),
        description = todo?.description.orEmpty(),
        priority = todo?.priority ?: TodoPriority.NONE,
        dueAt = todo?.dueAt,
        tagInput = todo?.tagNames?.joinToString(", ").orEmpty(),
    )

    fun closeEditor(generation: DataGeneration? = state.value.editor.generation) {
        if (!canEdit(generation)) return
        val previous = state.value.editor
        state.update { it.copy(editor = TodoEditorState()) }
        drainNotification(previous)
    }

    fun openNotificationDetail(id: Long, arrivalGeneration: DataGeneration? = null) {
        if (id <= 0L) return
        val request = notificationSequence.incrementAndGet()
        notificationGeneration = arrivalGeneration
        if (arrivalGeneration != null) {
            // Activity's original tag must reach lookup and deferral unchanged, even if UI observation lags.
            loadNotificationDetail(id, request, arrivalGeneration)
            return
        }
        // Activity may deliver and consume a cold-start request before any UI snapshot.
        // Capture once, then retain that same token through lookup, deferral and drain.
        val published = state.value.generation ?: observedGeneration
        notificationGeneration = published
        if (published == null) {
            if (access.maintenance.value != MaintenanceState.IDLE) return
            viewModelScope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    val token = access.capture()
                    withContext(Dispatchers.Main.immediate) {
                        if (request == notificationSequence.get()) {
                            notificationGeneration = token
                            loadNotificationDetail(id, request, token)
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) {
                        if (request == notificationSequence.get() && observedGeneration == null) metadataFailed()
                    }
                }
            }
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (request != notificationSequence.get() ||
                    (observedGeneration != null && observedGeneration != published)) return@launch
                val current = state.value.editor
                if (current.isOpen && current.generation == published) {
                    state.update { it.copy(editor = it.editor.copy(
                        pendingNotificationId = id,
                        pendingNotificationToken = request,
                        pendingNotificationGeneration = published,
                    )) }
                } else loadNotificationDetail(id, request, published)
            }
        }
    }

    private fun drainNotification(previous: TodoEditorState) {
        val id = previous.pendingNotificationId ?: return
        val request = previous.pendingNotificationToken ?: return
        val token = previous.pendingNotificationGeneration ?: return
        loadNotificationDetail(id, request, token)
    }

    private fun loadNotificationDetail(id: Long, request: Long, token: DataGeneration) {
        if (request != notificationSequence.get()) return
        // The ViewModel scope does not inherit a writer's held global permit.
        viewModelScope.launch(dispatcher) {
            try {
                while (request == notificationSequence.get()) {
                    try {
                        access.run(token) {
                            val target = repository.getAllTodos().firstOrNull { it.id == id }
                            withContext(Dispatchers.Main.immediate) {
                                observeGeneration(token)
                                if (request != notificationSequence.get()) return@withContext
                                val current = state.value.editor
                                if (current.isOpen) {
                                    state.update { it.copy(editor = it.editor.copy(
                                        pendingNotificationId = id,
                                        pendingNotificationToken = request,
                                        pendingNotificationGeneration = token,
                                    )) }
                                } else if (target == null) {
                                    state.update { it.copy(errorMessage = "通知对应的待办已删除或不存在") }
                                } else {
                                    state.update { it.copy(editor = editorFor(target, token)) }
                                }
                            }
                        }
                        return@launch
                    } catch (_: MaintenanceBusyException) {
                        // Read-only notification delivery may resume, but never acquire a new token.
                        access.maintenance.first { it == MaintenanceState.IDLE }
                        kotlinx.coroutines.yield()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == notificationSequence.get()) {
                    reportFailure(token, "读取通知待办失败", error)
                }
            }
        }
    }

    private fun canEdit(generation: DataGeneration?): Boolean =
        state.value.isAvailable && access.maintenance.value == MaintenanceState.IDLE &&
            state.value.editor.isOpen && !state.value.editor.isSaving &&
            generation != null && state.value.editor.generation == generation &&
            state.value.generation == generation

    private fun edit(generation: DataGeneration?, transform: (TodoEditorState) -> TodoEditorState) {
        if (canEdit(generation)) state.update { it.copy(editor = transform(it.editor)) }
    }

    fun onTitleChanged(value: String, generation: DataGeneration? = state.value.editor.generation) =
        edit(generation) { it.copy(title = value, validationMessage = null) }
    fun onDescriptionChanged(value: String, generation: DataGeneration? = state.value.editor.generation) =
        edit(generation) { it.copy(description = value) }
    fun onPriorityChanged(value: TodoPriority, generation: DataGeneration? = state.value.editor.generation) =
        edit(generation) { it.copy(priority = value) }
    fun onDueAtChanged(value: Instant?, generation: DataGeneration? = state.value.editor.generation) =
        edit(generation) { it.copy(dueAt = value) }
    fun onTagInputChanged(value: String, generation: DataGeneration? = state.value.editor.generation) =
        edit(generation) { it.copy(tagInput = value) }

    fun saveTodo(generation: DataGeneration? = state.value.editor.generation) {
        if (!canEdit(generation)) return
        val current = state.value.editor
        val token = eventToken(current.generation) ?: return
        val validation = TodoRules.validateTitle(current.title)
        if (validation != null) {
            edit(generation) { it.copy(validationMessage = validation) }
            return
        }
        val operation = Any().also { activeSaveOperation = it }
        state.update { it.copy(errorMessage = null, editor = it.editor.copy(isSaving = true)) }
        viewModelScope.launch(dispatcher) {
            var previous: TodoEditorState? = null
            try {
                access.run(token) {
                    TodoOperationCoordinator.run {
                        val existing = current.editingId?.let { id ->
                            requireNotNull(repository.getAllTodos().firstOrNull { it.id == id }) {
                                "待办已删除，请关闭旧表单"
                            }
                        }
                        val now = Instant.now()
                        val todo = (existing ?: Todo(title = current.title, createdAt = now)).copy(
                            title = current.title.trim(),
                            description = current.description.trim().ifEmpty { null },
                            priority = current.priority,
                            dueAt = current.dueAt,
                            updatedAt = now,
                            tagNames = current.tagInput.split(",").map(String::trim).filter(String::isNotEmpty),
                        )
                        val id = saveTodo(todo, todo.tagNames)
                        withContext(Dispatchers.Main.immediate) {
                            // A committed insert cannot be retried from an unsaved editor.
                            previous = state.value.editor
                            state.update { it.copy(editor = TodoEditorState()) }
                        }
                        try { reconcileReminder(todo.copy(id = id)) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.Main.immediate) {
                                state.update { it.copy(errorMessage = "待办已保存，但提醒设置失败：" + detail(error)) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "保存失败", error, editorError = true)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    // A previously committed save may finish after a newer draft starts saving.
                    if (activeSaveOperation === operation) {
                        activeSaveOperation = null
                        state.update { if (it.editor.generation == token)
                            it.copy(editor = it.editor.copy(isSaving = false)) else it }
                    }
                }
            }
            // Drain only after the outer permit and module Mutex have both ended.
            previous?.let(::drainNotification)
        }
    }

    fun toggleTodo(todo: Todo, generation: DataGeneration? = state.value.generation) {
        if (!state.value.isAvailable || generation != state.value.generation) return
        val token = eventToken(generation) ?: return
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) {
                    TodoOperationCoordinator.run {
                        val current = requireNotNull(repository.getAllTodos().firstOrNull { it.id == todo.id }) { "待办已删除" }
                        val completed = !current.isCompleted
                        toggleTodo(todo.id, completed)
                        try { reconcileReminder(current.copy(isCompleted = completed)) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.Main.immediate) {
                                state.update { it.copy(errorMessage = "待办状态已更新，但提醒设置失败：" + detail(error)) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "更新待办失败", error)
            }
        }
    }

    fun deleteTodo(todoId: Long, generation: DataGeneration? = state.value.generation) {
        if (!state.value.isAvailable || generation != state.value.generation) return
        val token = eventToken(generation) ?: return
        viewModelScope.launch(dispatcher) {
            try {
                access.run(token) {
                    TodoOperationCoordinator.run {
                        deleteTodoUseCase(todoId)
                        try { reminderScheduler.cancel(todoId) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.Main.immediate) {
                                state.update { it.copy(errorMessage = "待办已删除，但提醒清理失败：" + detail(error)) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure(token, "删除失败", error)
            }
        }
    }

    private fun eventToken(generation: DataGeneration?): DataGeneration? = try {
        access.eventToken(generation)
    } catch (_: IllegalStateException) { null }

    private suspend fun reportFailure(
        token: DataGeneration,
        action: String,
        error: Exception,
        unavailable: Boolean = false,
        editorError: Boolean = false,
    ) {
        if (error is MaintenanceBusyException || error is StaleGenerationException) return
        try {
            access.publishResult(token) {
                val message = action + "：" + detail(error)
                state.update { it.copy(
                    errorMessage = message,
                    isAvailable = if (unavailable) false else it.isAvailable,
                    isLoading = if (unavailable) false else it.isLoading,
                    editor = if (editorError && it.editor.generation == token)
                        it.editor.copy(isSaving = false, validationMessage = message) else it.editor,
                ) }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            withContext(Dispatchers.Main.immediate) {
                // An old fault cannot disable a newer observed generation or published snapshot.
                if (observedGeneration == token && (state.value.generation == null || state.value.generation == token)) {
                    metadataFailed()
                }
            }
        }
    }

    private fun metadataFailed() {
        state.update { it.copy(
            isAvailable = false,
            isLoading = false,
            errorMessage = "读取数据世代失败，请重新启动应用并检查数据",
        ) }
    }

    private fun detail(error: Exception) = error.message ?: "未知错误"

    private suspend fun reconcileReminder(todo: Todo) {
        val dueAt = todo.dueAt
        if (todo.isCompleted || dueAt == null) reminderScheduler.cancel(todo.id)
        else reminderScheduler.scheduleCurrent(todo.id, todo.title, dueAt)
    }

    private data class Snapshot(val visible: List<Todo>, val all: List<Todo>, val tags: List<Tag>)
    private data class ReadContext(val generation: DataGeneration, val phase: MaintenanceState, val filter: TodoFilter)
}
