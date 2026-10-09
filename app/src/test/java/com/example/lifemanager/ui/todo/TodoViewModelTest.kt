package com.example.lifemanager.ui.todo

import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.notification.ReminderSchedulerContract
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import com.example.lifemanager.domain.model.TodoDateFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import com.example.lifemanager.ui.common.testGenerationAccess
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodoViewModelTest {
    @Test
    fun `save rejects blank title and exposes validation message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: TodoViewModel? = null
        try {
            val model = TodoViewModel(
                repository = FakeTodoRepository(),
                reminderScheduler = NoOpReminderScheduler,
                dispatcher = dispatcher,
                access = testGenerationAccess(),
            )
            viewModel = model
            advanceUntilIdle()
            model.openEditor()
            model.onTitleChanged("   ")
            model.saveTodo()
            advanceUntilIdle()
            assertEquals("标题不能为空", model.uiState.value.editor.validationMessage)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `save persists a new todo through the repository`() = runTest {
        val repository = FakeTodoRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: TodoViewModel? = null
        try {
            val model = TodoViewModel(
                repository = repository,
                reminderScheduler = NoOpReminderScheduler,
                dispatcher = dispatcher,
                access = testGenerationAccess(),
            )
            viewModel = model
            advanceUntilIdle()
            model.openEditor()
            model.onTitleChanged("整理资料")
            model.saveTodo()
            advanceUntilIdle()
            assertEquals(listOf("整理资料"), repository.todos.value.map(Todo::title))
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `toggle marks an existing todo completed`() = runTest {
        val repository = FakeTodoRepository().apply {
            todos.value = listOf(Todo(id = 7, title = "完成我"))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: TodoViewModel? = null
        try {
            val model = TodoViewModel(repository, NoOpReminderScheduler, dispatcher, testGenerationAccess())
            viewModel = model
            advanceUntilIdle()
            model.toggleTodo(repository.todos.value.single())
            advanceUntilIdle()
            assertTrue(repository.todos.value.single().isCompleted)
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `delete removes an existing todo`() = runTest {
        val repository = FakeTodoRepository().apply {
            todos.value = listOf(Todo(id = 8, title = "删除我"))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var viewModel: TodoViewModel? = null
        try {
            val model = TodoViewModel(repository, NoOpReminderScheduler, dispatcher, testGenerationAccess())
            viewModel = model
            advanceUntilIdle()
            model.deleteTodo(8)
            advanceUntilIdle()
            assertFalse(repository.todos.value.any { it.id == 8L })
        } finally {
            viewModel?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `reminder failure after insert does not leave a retryable new draft`() = runTest {
        val repository = FakeTodoRepository()
        withModel(repository, FailingReminderScheduler) { model ->
            model.openEditor()
            model.onTitleChanged("已经保存")
            model.onDueAtChanged(Instant.now().plusSeconds(3600))
            model.saveTodo()
            advanceUntilIdle()
            assertEquals(1, repository.todos.value.size)
            assertFalse(model.uiState.value.editor.isOpen)
            assertTrue(assertNotNull(model.uiState.value.errorMessage).contains("已保存"))
            model.saveTodo()
            advanceUntilIdle()
            assertEquals(1, repository.todos.value.size)
        }
    }

    @Test fun `double save before dispatch only creates one record`() = runTest {
        val repository = FakeTodoRepository()
        withModel(repository) { model ->
            model.openEditor()
            model.onTitleChanged("只保存一次")
            model.saveTodo()
            model.saveTodo()
            advanceUntilIdle()
            assertEquals(1, repository.todos.value.size)
        }
    }

    @Test fun `saving a filtered out completed todo preserves persisted metadata`() = runTest {
        val original = Todo(id = 7, title = "原任务", isCompleted = true,
            completedAt = Instant.parse("2026-09-28T12:00:00Z"),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"), parentId = 3, sortOrder = 99)
        val repository = FakeTodoRepository().apply { todos.value = listOf(original) }
        withModel(repository) { model ->
            model.onFilterChanged(TodoFilter(dateFilter = TodoDateFilter.ALL, query = "不匹配"))
            advanceUntilIdle()
            assertTrue(model.uiState.value.todos.isEmpty())
            model.openEditor(original)
            model.onTitleChanged("改名")
            model.saveTodo()
            advanceUntilIdle()
            val saved = repository.todos.value.single()
            assertTrue(saved.isCompleted)
            assertEquals(original.completedAt, saved.completedAt)
            assertEquals(original.createdAt, saved.createdAt)
            assertEquals(3L, saved.parentId)
            assertEquals(99, saved.sortOrder)
        }
    }

    @Test fun `deleted edit target is not recreated by a stale draft`() = runTest {
        val original = Todo(id = 7, title = "已经删除")
        val repository = FakeTodoRepository().apply { todos.value = listOf(original) }
        withModel(repository) { model ->
            model.openEditor(original)
            repository.deleteTodo(7)
            advanceUntilIdle()
            model.onTitleChanged("旧草稿")
            model.saveTodo()
            advanceUntilIdle()
            assertTrue(repository.todos.value.isEmpty())
            assertNotNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun `notification lookup finishing after draft opened is deferred rather than lost`() = runTest {
        val repository = FakeTodoRepository().apply { todos.value = listOf(Todo(id = 7, title = "通知任务")) }
        withModel(repository) { model ->
            val gate = CompletableDeferred<Unit>()
            repository.readGate = gate
            model.openNotificationDetail(7)
            runCurrent()
            model.openEditor()
            model.onTitleChanged("新的草稿")
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("新的草稿", model.uiState.value.editor.title)
            assertEquals(7L, model.uiState.value.editor.pendingNotificationId)
            model.closeEditor()
            advanceUntilIdle()
            assertEquals(7L, model.uiState.value.editor.editingId)
        }
    }

    @Test fun `database failure keeps draft and exposes error inside editor`() = runTest {
        val repository = FakeTodoRepository().apply { failSave = true }
        withModel(repository) { model ->
            model.openEditor()
            model.onTitleChanged("保留我的草稿")
            model.saveTodo()
            advanceUntilIdle()
            assertTrue(repository.todos.value.isEmpty())
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals("保留我的草稿", model.uiState.value.editor.title)
            assertFalse(model.uiState.value.editor.isSaving)
            assertNotNull(model.uiState.value.editor.validationMessage)
        }
    }

    @Test fun `notification received during save opens only after draft committed`() = runTest {
        val repository = FakeTodoRepository().apply { todos.value = listOf(Todo(id = 7, title = "通知任务")) }
        withModel(repository) { model ->
            model.openEditor()
            model.onTitleChanged("新待办")
            model.saveTodo()
            model.onTitleChanged("不应改变正在提交的快照")
            model.closeEditor()
            model.openNotificationDetail(7)
            advanceUntilIdle()
            assertEquals(setOf("通知任务", "新待办"), repository.todos.value.map { it.title }.toSet())
            assertEquals(7L, model.uiState.value.editor.editingId)
        }
    }

    @Test fun `notification lookup remains deliverable after intervening draft closes`() = runTest {
        val repository = FakeTodoRepository().apply { todos.value = listOf(Todo(id = 7, title = "通知任务")) }
        withModel(repository) { model ->
            val gate = CompletableDeferred<Unit>()
            repository.readGate = gate
            model.openNotificationDetail(7)
            runCurrent()
            model.openEditor()
            model.onTitleChanged("临时草稿")
            model.closeEditor()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(7L, model.uiState.value.editor.editingId)
        }
    }

    @Test fun `draining old deferred notification cannot override newer tap during alarm setup`() = runTest {
        val repository = FakeTodoRepository().apply {
            todos.value = listOf(Todo(id = 7, title = "旧通知"), Todo(id = 8, title = "新通知"))
        }
        lateinit var currentModel: TodoViewModel
        val alarms = object : ReminderSchedulerContract {
            override fun schedule(todoId: Long, title: String, dueAt: Instant) { currentModel.openNotificationDetail(8) }
            override fun cancel(todoId: Long) = Unit
        }
        withModel(repository, alarms) { model ->
            currentModel = model
            model.openEditor()
            model.onTitleChanged("新待办")
            model.onDueAtChanged(Instant.now().plusSeconds(3600))
            model.openNotificationDetail(7)
            model.saveTodo()
            advanceUntilIdle()
            assertEquals(8L, model.uiState.value.editor.editingId)
        }
    }

    @Test fun `superseded lookup failure does not pollute newer successful notification`() = runTest {
        val repository = FakeTodoRepository().apply {
            todos.value = listOf(Todo(id = 7, title = "旧通知"), Todo(id = 8, title = "新通知"))
        }
        withModel(repository) { model ->
            val gate = CompletableDeferred<Unit>()
            repository.readGate = gate
            repository.readFailure = IllegalStateException("旧查询失败")
            model.openNotificationDetail(7)
            runCurrent()
            repository.readGate = null
            repository.readFailure = null
            model.openNotificationDetail(8)
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(8L, model.uiState.value.editor.editingId)
            kotlin.test.assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun `delete waits for in flight reminder delivery coordinator`() = runTest {
        val repository = FakeTodoRepository().apply { todos.value = listOf(Todo(id = 7, title = "待删除")) }
        withModel(repository) { model ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delivery = backgroundScope.launch {
                TodoOperationCoordinator.run { entered.complete(Unit); release.await() }
            }
            runCurrent()
            entered.await()
            try {
                model.deleteTodo(7)
                runCurrent()
                assertEquals(7L, repository.todos.value.single().id)
            } finally { release.complete(Unit) }
            delivery.join()
            advanceUntilIdle()
            assertTrue(repository.todos.value.isEmpty())
        }
    }

    @Test fun `committed save waits for main editor handoff before alarm side effects`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val ioExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "todo-save-io") }
        val io = ioExecutor.asCoroutineDispatcher()
        val saved = CountDownLatch(1)
        val returnFromSave = CountDownLatch(1)
        val alarmStarted = AtomicBoolean()
        val persisted = FakeTodoRepository().apply { todos.value = listOf(Todo(id = 7, title = "通知目标")) }
        val repository = object : TodoRepository by persisted {
            override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long {
                val id = persisted.saveTodo(todo, tagNames)
                saved.countDown()
                check(returnFromSave.await(5, TimeUnit.SECONDS))
                return id
            }
        }
        val alarms = object : ReminderSchedulerContract {
            override fun schedule(todoId: Long, title: String, dueAt: Instant) {
                alarmStarted.set(true)
            }
            override fun cancel(todoId: Long) = Unit
        }
        Dispatchers.setMain(main)
        val model = TodoViewModel(repository, alarms, io, testGenerationAccess())
        try {
            val initialDeadline = System.nanoTime() + 5_000_000_000L
            do { runCurrent(); if (model.uiState.value.isAvailable) break; Thread.yield() }
            while (System.nanoTime() < initialDeadline)
            assertTrue(model.uiState.value.isAvailable)
            model.openEditor()
            model.onTitleChanged("提交中的草稿")
            model.onDueAtChanged(Instant.now().plusSeconds(3600))
            model.saveTodo()
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            model.openNotificationDetail(7)
            runCurrent() // Drain prior emissions before observing the commit handoff.
            // FIFO sentinel: the running save must return/suspend before this can run.
            // Main stays paused, so only a correct Main handoff can precede the sentinel.
            val ioQueueReached = CountDownLatch(1)
            ioExecutor.execute { ioQueueReached.countDown() }
            returnFromSave.countDown()
            assertTrue(ioQueueReached.await(5, TimeUnit.SECONDS))
            assertFalse(alarmStarted.get(), "Main must consume the editor state before IO alarm side effects")
            val timeout = System.nanoTime() + 5_000_000_000L
            do { runCurrent(); if (model.uiState.value.editor.editingId == 7L) break; Thread.yield() }
            while (System.nanoTime() < timeout)
            assertEquals(7L, model.uiState.value.editor.editingId)
            assertTrue(alarmStarted.get())
            assertEquals(2, persisted.todos.value.size)
        } finally {
            returnFromSave.countDown()
            model.viewModelScope.cancel()
            io.close()
            Dispatchers.resetMain()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withModel(
        repository: FakeTodoRepository,
        scheduler: ReminderSchedulerContract = NoOpReminderScheduler,
        block: suspend (TodoViewModel) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val model = TodoViewModel(repository, scheduler, dispatcher, testGenerationAccess())
        try { advanceUntilIdle(); block(model) }
        finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private object FailingReminderScheduler : ReminderSchedulerContract {
        override fun schedule(todoId: Long, title: String, dueAt: Instant): Unit = error("alarm service unavailable")
        override fun cancel(todoId: Long) = Unit
    }

    private class FakeTodoRepository : TodoRepository {
        var readGate: CompletableDeferred<Unit>? = null
        var readFailure: Exception? = null
        var failSave = false
        val todos = MutableStateFlow<List<Todo>>(emptyList())
        private val tags = MutableStateFlow<List<Tag>>(emptyList())

        override fun observeTodos(filter: TodoFilter): Flow<List<Todo>> = todos.map { list ->
            list.filter { filter.query.isBlank() || it.title.contains(filter.query) }
        }
        override fun observeTags(): Flow<List<Tag>> = tags
        override suspend fun getAllTodos(): List<Todo> {
            val gate = readGate
            val failure = readFailure
            gate?.await()
            if (failure != null) throw failure
            return todos.value
        }
        override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long {
            if (failSave) error("database unavailable")
            val saved = todo.copy(id = if (todo.id == 0L) (todos.value.size + 1).toLong() else todo.id)
            todos.value = todos.value.filterNot { it.id == saved.id } + saved
            return saved.id
        }
        override suspend fun deleteTodo(id: Long) {
            todos.value = todos.value.filterNot { it.id == id }
        }
        override suspend fun setCompleted(id: Long, completed: Boolean) {
            todos.value = todos.value.map { if (it.id == id) it.copy(isCompleted = completed) else it }
        }
    }

    private object NoOpReminderScheduler : ReminderSchedulerContract {
        override fun schedule(todoId: Long, title: String, dueAt: Instant) = Unit
        override fun cancel(todoId: Long) = Unit
    }
}
