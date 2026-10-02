package com.example.lifemanager.ui.schedule

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import com.example.lifemanager.ui.settings.LocalDateFormat
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.*
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.notification.ReminderSchedulerContract
import com.example.lifemanager.ui.navigation.NavGraph
import com.example.lifemanager.ui.navigation.ScheduleNavigationRequest
import com.example.lifemanager.ui.settings.TestSettingsOwner
import com.example.lifemanager.ui.todo.TodoViewModel
import com.example.lifemanager.ui.theme.LifeManagerTheme
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduleScreenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: ScheduleViewModel
    private var todoModel: TodoViewModel? = null
    private var settingsOwner: TestSettingsOwner? = null
    @After fun cleanup() { if (::model.isInitialized) model.viewModelScope.cancel(); todoModel?.viewModelScope?.cancel(); settingsOwner?.close() }
    @Test fun notificationPreservesDraftBeforeOpeningTarget() {
        model = ScheduleViewModel(Records(), NoAlarms, Dispatchers.IO)
        val request = mutableStateOf<Long?>(null)
        model.openEditor(); model.onTitleChanged("不要覆盖的日程草稿")
        compose.setContent { LifeManagerTheme { ScheduleScreen(initialScheduleId = request.value, viewModel = model) } }
        compose.waitForIdle()
        compose.runOnIdle { request.value = 42 }
        compose.waitForIdle()
        assertEquals("不要覆盖的日程草稿", model.uiState.value.editor.title)
        compose.runOnIdle { model.closeEditor() }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.editingId == 42L }
    }
    @Test fun returningFromSettingsUsesRetainedScheduleDraftAndConsumesRequest() {
        model = ScheduleViewModel(Records(), NoAlarms, Dispatchers.IO)
        val todos = TodoViewModel(EmptyTodos(), NoTodoAlarms, Dispatchers.IO)
        todoModel = todos
        val request = mutableStateOf<ScheduleNavigationRequest?>(null)
        val settings = TestSettingsOwner().also { settingsOwner = it }
        compose.setContent { LifeManagerTheme { NavGraph(todoViewModel = todos, scheduleViewModel = model, settingsViewModel = settings.model,
            scheduleRequest = request.value, onScheduleConsumed = { token ->
                if (request.value?.token == token) request.value = null
            }) } }
        compose.waitForIdle()
        compose.onNode(hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("已完成模块").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { model.openEditor(); model.onTitleChanged("跨模块的日程草稿"); request.value = ScheduleNavigationRequest(1, 42) }
        compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.pendingNotificationId == 42L && request.value == null }
        compose.onNodeWithText("跨模块的日程草稿").assertExists()
        compose.onNodeWithText("通知日程等待查看；保存或关闭当前草稿后打开").assertExists()
    }
    @Test fun savingVisiblyDisablesEditorInputs() {
        val gate = CompletableDeferred<Unit>()
        val repository = Records().apply { saveGate = gate }
        model = ScheduleViewModel(repository, NoAlarms, Dispatchers.IO)
        model.openEditor(); model.onTitleChanged("正在提交的草稿")
        model.onStartChanged(Instant.parse("2026-10-06T01:00:00Z"))
        model.onEndChanged(Instant.parse("2026-10-06T02:00:00Z"))
        compose.setContent { LifeManagerTheme { ScheduleScreen(viewModel = model) } }
        try {
            compose.runOnIdle { model.saveSchedule() }
            compose.waitUntil(5000) { compose.waitForIdle(); model.uiState.value.editor.isSaving }
            compose.onNodeWithText("标题").assertIsNotEnabled()
            compose.onNode(isToggleable()).assertIsNotEnabled()
            compose.onAllNodesWithText("选择").assertAll(isNotEnabled())
            compose.onNodeWithText("地点").assertIsNotEnabled()
            compose.onNodeWithText("参与者（文本）").assertIsNotEnabled()
            compose.onNodeWithText("备注").assertIsNotEnabled()
            compose.onNodeWithText("提前提醒分钟（留空关闭）").assertIsNotEnabled()
            compose.onNodeWithText("每天").assertIsNotEnabled()
            compose.onNodeWithText("已选").assertIsNotEnabled()
        } finally { gate.complete(Unit) }
    }

    @Test fun allDayEditorUsesSelectedDisplayFormatWithoutChangingCalendarDates() {
        model = ScheduleViewModel(Records(), NoAlarms, Dispatchers.IO)
        model.openEditor()
        model.onAllDayChanged(true)
        val day = java.time.LocalDate.of(2026, 10, 2)
        model.onAllDayDatesChanged(day, day)
        compose.setContent { LifeManagerTheme { CompositionLocalProvider(LocalDateFormat provides DateFormat.DMY) {
            ScheduleScreen(viewModel = model)
        } } }
        compose.onNodeWithText("开始日期：02-10-2026").assertExists()
        assertEquals(day, model.uiState.value.editor.allDayStartDate)
    }
    private object NoTodoAlarms : ReminderSchedulerContract {
        override fun schedule(todoId: Long, title: String, dueAt: Instant) = Unit
        override fun cancel(todoId: Long) = Unit
    }
    private class EmptyTodos : TodoRepository {
        override fun observeTodos(filter: TodoFilter) = MutableStateFlow(emptyList<Todo>())
        override fun observeTags() = MutableStateFlow(emptyList<Tag>())
        override suspend fun getAllTodos() = emptyList<Todo>()
        override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long = error("Unused")
        override suspend fun deleteTodo(id: Long): Unit = error("Unused")
        override suspend fun setCompleted(id: Long, completed: Boolean): Unit = error("Unused")
    }
    private object NoAlarms : ScheduleReminderSchedulerContract {
        override fun schedule(schedule: Schedule) = Unit
        override fun cancel(scheduleId: Long) = Unit
    }
    private class Records : ScheduleRepository {
        var saveGate: CompletableDeferred<Unit>? = null
        private val rows = MutableStateFlow(listOf(Schedule(id = 42, title = "通知目标", startAt = Instant.parse("2026-10-05T01:00:00Z"), endAt = Instant.parse("2026-10-05T02:00:00Z"), timeZone = "Asia/Shanghai")))
        override fun observeSchedules() = rows
        override suspend fun getSchedules() = rows.value
        override suspend fun getExceptions(scheduleId: Long) = emptyList<ScheduleException>()
        override suspend fun saveException(exception: ScheduleException): Unit = error("Unused")
        override suspend fun saveSchedule(schedule: Schedule): Long { checkNotNull(saveGate).await(); return 43L }
        override suspend fun deleteSchedule(id: Long): Unit = error("Unused")
    }
}
