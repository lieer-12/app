package com.example.lifemanager.ui.todo

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.notification.ReminderSchedulerContract
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.ui.schedule.ScheduleViewModel
import com.example.lifemanager.ui.theme.LifeManagerTheme
import com.example.lifemanager.ui.navigation.NavGraph
import com.example.lifemanager.ui.navigation.TodoNavigationRequest
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TodoScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: TodoViewModel
    private var scheduleModel: ScheduleViewModel? = null
    private val oldZone = TimeZone.getDefault()
    private val requestId = mutableStateOf<Long?>(null)

    @After fun cleanup() { if (::model.isInitialized) model.viewModelScope.cancel(); scheduleModel?.viewModelScope?.cancel(); TimeZone.setDefault(oldZone) }

    @Test fun notificationOpensTargetHiddenBySearch() {
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "通知目标"))), NoAlarms, Dispatchers.IO)
        model.onFilterChanged(TodoFilter(dateFilter = TodoDateFilter.ALL, query = "不匹配"))
        show()
        compose.waitUntil(5000) { !model.uiState.value.isLoading }
        compose.runOnIdle { requestId.value = 42 }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.editingId == 42L }
        assertTrue(model.uiState.value.todos.isEmpty())
    }

    @Test fun notificationPreservesDraftThenOpensTargetAfterClose() {
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "通知目标"))), NoAlarms, Dispatchers.IO)
        model.openEditor()
        model.onTitleChanged("不要覆盖的草稿")
        show()
        compose.runOnIdle { requestId.value = 42 }
        compose.waitForIdle()
        assertEquals("不要覆盖的草稿", model.uiState.value.editor.title)
        compose.runOnIdle { model.closeEditor() }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.editingId == 42L }
    }

    @Test fun confirmingExistingLocalDateDoesNotShiftItBackOneDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val dueAt = Instant.parse("2026-09-28T16:30:00Z") // September 29, 00:30 locally.
        val todo = Todo(id = 42, title = "凌晨任务", dueAt = dueAt)
        model = TodoViewModel(ScreenRepository(listOf(todo)), NoAlarms, Dispatchers.IO)
        model.openEditor(todo)
        show()
        compose.waitUntil(5000) { model.uiState.value.editor.editingId == 42L && !model.uiState.value.isLoading }
        compose.waitForIdle()
        compose.onNodeWithText("日期").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("确定").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确定").performClick()
        assertEquals(dueAt, model.uiState.value.editor.dueAt)
    }

    private fun show() { compose.setContent { LifeManagerTheme {
        TodoScreen(initialTodoId = requestId.value, onOpenSettings = {}, viewModel = model)
    } }; compose.waitForIdle() }

    @Test fun notificationReturningFromSettingsUsesRetainedTodoDraftOwner() {
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "通知目标"))), NoAlarms, Dispatchers.IO)
        val request = mutableStateOf<TodoNavigationRequest?>(null)
        val schedules = ScheduleViewModel(EmptySchedules(), NoScheduleAlarms, Dispatchers.IO)
        scheduleModel = schedules
        compose.setContent { LifeManagerTheme { NavGraph(todoViewModel = model, scheduleViewModel = schedules, todoRequest = request.value,
            onTodoConsumed = { token -> if (request.value?.token == token) request.value = null }) } }
        compose.waitForIdle()
        compose.onNode(hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("已完成模块").fetchSemanticsNodes().isNotEmpty() }
        // Represents the retained Todo owner while another destination is visible.
        compose.runOnIdle { model.openEditor(); model.onTitleChanged("跨模块保留的草稿"); request.value = TodoNavigationRequest(1, 42) }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.pendingNotificationId == 42L }
        assertEquals("跨模块保留的草稿", model.uiState.value.editor.title)
        compose.onNodeWithText("跨模块保留的草稿").assertExists()
    }

    private object NoAlarms : ReminderSchedulerContract {
        override fun schedule(todoId: Long, title: String, dueAt: Instant) = Unit
        override fun cancel(todoId: Long) = Unit
    }
    private object NoScheduleAlarms : ScheduleReminderSchedulerContract {
        override fun schedule(schedule: Schedule) = Unit
        override fun cancel(scheduleId: Long) = Unit
    }
    private class EmptySchedules : ScheduleRepository {
        override fun observeSchedules() = MutableStateFlow(emptyList<Schedule>())
        override suspend fun getSchedules() = emptyList<Schedule>()
        override suspend fun getExceptions(scheduleId: Long) = emptyList<ScheduleException>()
        override suspend fun saveException(exception: ScheduleException): Unit = error("Not used")
        override suspend fun saveSchedule(schedule: Schedule): Long = error("Not used")
        override suspend fun deleteSchedule(id: Long): Unit = error("Not used")
    }
    private class ScreenRepository(initial: List<Todo>) : TodoRepository {
        private val todos = MutableStateFlow(initial)
        override fun observeTodos(filter: TodoFilter) = todos.map { values ->
            values.filter { filter.query.isBlank() || it.title.contains(filter.query) }
        }
        override fun observeTags() = MutableStateFlow(emptyList<Tag>())
        override suspend fun getAllTodos() = todos.value
        override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long = error("Not used")
        override suspend fun deleteTodo(id: Long): Unit = error("Not used")
        override suspend fun setCompleted(id: Long, completed: Boolean): Unit = error("Not used")
    }
}
