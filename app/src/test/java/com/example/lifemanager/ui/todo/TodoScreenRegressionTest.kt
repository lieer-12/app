package com.example.lifemanager.ui.todo

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.lifemanager.ui.settings.LocalDateFormat
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
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
import com.example.lifemanager.ui.navigation.TodoNavigationViewModel
import com.example.lifemanager.ui.settings.TestSettingsOwner
import com.example.lifemanager.ui.common.testGenerationAccess
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
    private var settingsOwner: TestSettingsOwner? = null
    private var navigationOwner: TodoNavigationViewModel? = null
    private val oldZone = TimeZone.getDefault()
    private val requestId = mutableStateOf<Long?>(null)

    @After fun cleanup() { if (::model.isInitialized) model.viewModelScope.cancel(); scheduleModel?.viewModelScope?.cancel(); navigationOwner?.viewModelScope?.cancel(); settingsOwner?.close(); TimeZone.setDefault(oldZone) }

    @Test fun maintenanceMarksAddTodoFabDisabledWhileKeepingAvailableSnapshot() {
        val generations = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(generations)
        val access = GenerationAccess(generations, coordinator)
        val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "维护期间保留的记录"))),
            NoAlarms, Dispatchers.IO, access)
        try {
            show()
            waitUntilAvailable()
            compose.onNodeWithContentDescription("添加待办").assertIsEnabled()
            maintenanceScope.launch { coordinator.withSession { awaitCancellation() } }
            compose.waitUntil(5000) {
                compose.waitForIdle()
                coordinator.state.value == MaintenanceState.READY && model.uiState.value.isMaintaining
            }
            assertTrue(model.uiState.value.isAvailable)
            compose.onNodeWithText("维护期间保留的记录").assertExists()
            compose.onNodeWithContentDescription("添加待办").assertIsNotEnabled()
        } finally { maintenanceScope.cancel() }
    }

    @Test fun notificationOpensTargetHiddenBySearch() {
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "通知目标"))), NoAlarms, Dispatchers.IO, testGenerationAccess())
        model.onFilterChanged(TodoFilter(dateFilter = TodoDateFilter.ALL, query = "不匹配"))
        show()
        waitUntilAvailable()
        compose.runOnIdle { requestId.value = 42 }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.editingId == 42L }
        assertTrue(model.uiState.value.todos.isEmpty())
    }

    @Test fun notificationPreservesDraftThenOpensTargetAfterClose() {
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "通知目标"))), NoAlarms, Dispatchers.IO, testGenerationAccess())
        show()
        waitUntilAvailable()
        compose.runOnIdle { model.openEditor(); model.onTitleChanged("不要覆盖的草稿") }
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
        model = TodoViewModel(ScreenRepository(listOf(todo)), NoAlarms, Dispatchers.IO, testGenerationAccess())
        show()
        waitUntilAvailable()
        compose.runOnIdle { model.openEditor(todo) }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.editingId == 42L && !model.uiState.value.isLoading }
        compose.waitForIdle()
        compose.onNodeWithText("日期").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("确定").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确定").performClick()
        assertEquals(dueAt, model.uiState.value.editor.dueAt)
    }

    @Test fun todoDeadlineDisplayUsesPreferenceWithoutChangingItsInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val due = Instant.parse("2026-10-01T23:30:00Z")
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "日期展示", dueAt = due))), NoAlarms, Dispatchers.IO, testGenerationAccess())
        compose.setContent { LifeManagerTheme { CompositionLocalProvider(LocalDateFormat provides DateFormat.DMY) {
            TodoScreen(viewModel = model, onOpenSettings = {})
        } } }
        waitUntilAvailable()
        compose.runOnIdle { model.openEditor(Todo(id = 42, title = "日期展示", dueAt = due)) }
        compose.onNodeWithText("02-10-2026 07:30").assertExists()
        assertEquals(due, model.uiState.value.editor.dueAt)
    }

    private fun show() { compose.setContent { LifeManagerTheme {
        TodoScreen(initialTodoId = requestId.value, onOpenSettings = {}, viewModel = model)
    } }; compose.waitForIdle() }

    private fun waitUntilAvailable() {
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.isAvailable }
    }

    @Test fun notificationReturningFromSettingsUsesRetainedTodoDraftOwner() {
        val access = testGenerationAccess()
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "通知目标"))), NoAlarms, Dispatchers.IO, access)
        val navigation = TodoNavigationViewModel(access, Dispatchers.IO).also { navigationOwner = it }
        val schedules = ScheduleViewModel(EmptySchedules(), NoScheduleAlarms, Dispatchers.IO, access)
        scheduleModel = schedules
        val settings = TestSettingsOwner().also { settingsOwner = it }
        compose.setContent { LifeManagerTheme {
            val request by navigation.pending.collectAsState()
            NavGraph(todoViewModel = model, scheduleViewModel = schedules, settingsViewModel = settings.model,
                todoRequest = request, todoNavigation = navigation, onTodoConsumed = navigation::consume)
        } }
        compose.waitForIdle()
        compose.onNode(hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("已完成模块").fetchSemanticsNodes().isNotEmpty() }
        waitUntilAvailable()
        // Represents the retained Todo owner while another destination is visible.
        compose.runOnIdle { model.openEditor(); model.onTitleChanged("跨模块保留的草稿"); navigation.open(42) }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.pendingNotificationId == 42L }
        assertEquals("跨模块保留的草稿", model.uiState.value.editor.title)
        compose.onNodeWithText("跨模块保留的草稿").assertExists()
    }

    @Test fun coldStartWithNotificationWaitsForTheGraphThenOpensTheTarget() {
        val access = testGenerationAccess()
        model = TodoViewModel(ScreenRepository(listOf(Todo(id = 42, title = "冷启动目标"))), NoAlarms, Dispatchers.IO, access)
        val navigation = TodoNavigationViewModel(access, Dispatchers.IO).also { navigationOwner = it }
        val schedules = ScheduleViewModel(EmptySchedules(), NoScheduleAlarms, Dispatchers.IO, access)
        scheduleModel = schedules
        val settings = TestSettingsOwner().also { settingsOwner = it }
        var consumed = false
        navigation.open(42)
        compose.setContent { LifeManagerTheme {
            val request by navigation.pending.collectAsState()
            NavGraph(todoViewModel = model, scheduleViewModel = schedules, settingsViewModel = settings.model,
                todoRequest = request, todoNavigation = navigation,
                onTodoConsumed = { navigation.consume(it); consumed = true })
        } }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.editingId == 42L }
        compose.onNode(hasText("冷启动目标") and hasSetTextAction()).assertExists()
        assertTrue(consumed)
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
