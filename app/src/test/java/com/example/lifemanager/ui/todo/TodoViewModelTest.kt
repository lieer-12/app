package com.example.lifemanager.ui.todo

import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.notification.ReminderSchedulerContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoViewModelTest {
    @Test
    fun `save rejects blank title and exposes validation message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = TodoViewModel(
                repository = FakeTodoRepository(),
                reminderScheduler = NoOpReminderScheduler,
                dispatcher = dispatcher,
            )
            viewModel.onTitleChanged("   ")
            viewModel.saveTodo()
            advanceUntilIdle()
            assertEquals("标题不能为空", viewModel.uiState.value.editor.validationMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `save persists a new todo through the repository`() = runTest {
        val repository = FakeTodoRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = TodoViewModel(
                repository = repository,
                reminderScheduler = NoOpReminderScheduler,
                dispatcher = dispatcher,
            )
            viewModel.onTitleChanged("整理资料")
            viewModel.saveTodo()
            advanceUntilIdle()
            assertEquals(listOf("整理资料"), repository.todos.value.map(Todo::title))
        } finally {
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
        try {
            val viewModel = TodoViewModel(repository, NoOpReminderScheduler, dispatcher)
            viewModel.toggleTodo(repository.todos.value.single())
            advanceUntilIdle()
            assertTrue(repository.todos.value.single().isCompleted)
        } finally {
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
        try {
            val viewModel = TodoViewModel(repository, NoOpReminderScheduler, dispatcher)
            viewModel.deleteTodo(8)
            advanceUntilIdle()
            assertFalse(repository.todos.value.any { it.id == 8L })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeTodoRepository : TodoRepository {
        val todos = MutableStateFlow<List<Todo>>(emptyList())
        private val tags = MutableStateFlow<List<Tag>>(emptyList())

        override fun observeTodos(filter: TodoFilter): Flow<List<Todo>> = todos.map { list -> list }
        override fun observeTags(): Flow<List<Tag>> = tags
        override suspend fun getAllTodos(): List<Todo> = todos.value
        override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long {
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
