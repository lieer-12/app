package com.example.lifemanager.ui.schedule

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.*
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.ui.common.testGenerationAccess
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleEditorRegressionTest {
    @Test fun `editing retains latest creation timezone and hidden recurrence parameters`() = runTest {
        val original = meeting(7).copy(createdAt = Instant.parse("2020-01-01T00:00:00Z"), repeatRule = ScheduleRepeatRule.CUSTOM)
        val repository = Records(listOf(original))
        withModel(repository) { model ->
            model.openEditor(original)
            val latest = original.copy(timeZone = "America/New_York", repeatInterval = 3,
                repeatDaysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), repeatEndDate = LocalDate.of(2028, 1, 1))
            repository.rows.value = listOf(latest)
            model.onTitleChanged("新标题")
            model.saveSchedule()
            advanceUntilIdle()
            val saved = repository.rows.value.single()
            assertEquals("新标题", saved.title)
            assertEquals(latest.createdAt, saved.createdAt)
            assertEquals("America/New_York", saved.timeZone)
            assertEquals(3, saved.repeatInterval)
            assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), saved.repeatDaysOfWeek)
            assertEquals(LocalDate.of(2028, 1, 1), saved.repeatEndDate)
        }
    }
    @Test fun `invalid nonblank reminder input is rejected rather than disabling reminder`() = runTest {
        val repository = Records()
        withModel(repository) { model ->
            for (invalid in listOf("abc", "2147483648", "1.5", "-1")) {
                model.openEditor(); model.onTitleChanged("会议"); model.onReminderChanged(invalid)
                model.saveSchedule(); advanceUntilIdle()
                assertTrue(repository.rows.value.isEmpty(), invalid)
                assertTrue(model.uiState.value.editor.isOpen)
                assertNotNull(model.uiState.value.editor.validationMessage, invalid)
            }
        }
    }
    @Test fun `double save creates only one schedule`() = runTest {
        val repository = Records().apply { saveGate = CompletableDeferred() }
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("只保存一次")
            model.saveSchedule(); model.saveSchedule(); runCurrent()
            repository.saveGate!!.complete(Unit); advanceUntilIdle()
            assertEquals(1, repository.rows.value.size)
        }
    }
    @Test fun `editing dismiss and replacement are blocked while committing`() = runTest {
        val repository = Records().apply { saveGate = CompletableDeferred() }
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("提交快照"); model.saveSchedule(); runCurrent()
            model.onTitleChanged("不应写入"); model.closeEditor(); model.openEditor()
            runCurrent()
            assertTrue(model.uiState.value.editor.isOpen)
            assertTrue(model.uiState.value.editor.isSaving)
            assertEquals("提交快照", model.uiState.value.editor.title)
            repository.saveGate!!.complete(Unit); advanceUntilIdle()
            assertEquals("提交快照", repository.rows.value.single().title)
        }
    }
    @Test fun `reminder failure after insert is not a retryable unsaved draft`() = runTest {
        val repository = Records()
        withModel(repository, BrokenAlarms) { model ->
            model.openEditor(); model.onTitleChanged("已保存"); model.saveSchedule(); advanceUntilIdle()
            assertEquals(1, repository.rows.value.size)
            assertFalse(model.uiState.value.editor.isOpen)
            assertTrue(assertNotNull(model.uiState.value.errorMessage).contains("已保存"))
            model.saveSchedule(); advanceUntilIdle()
            assertEquals(1, repository.rows.value.size)
        }
    }
    @Test fun `database failure exposes editable draft error`() = runTest {
        val repository = Records().apply { failSave = true }
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("保留草稿"); model.saveSchedule(); advanceUntilIdle()
            assertTrue(model.uiState.value.editor.isOpen)
            assertFalse(model.uiState.value.editor.isSaving)
            assertEquals("保留草稿", model.uiState.value.editor.title)
            assertNotNull(model.uiState.value.editor.validationMessage)
            assertTrue(repository.rows.value.isEmpty())
        }
    }
    @Test fun `missing edit target cannot be inserted by stale form`() = runTest {
        val original = meeting(7)
        val repository = Records(listOf(original))
        withModel(repository) { model ->
            model.openEditor(original); repository.rows.value = emptyList()
            model.onTitleChanged("旧草稿"); model.saveSchedule(); advanceUntilIdle()
            assertTrue(repository.rows.value.isEmpty())
            assertNotNull(model.uiState.value.editor.validationMessage)
        }
    }
    @Test fun `changing form invalidates previously confirmed conflict`() = runTest {
        val repository = Records(listOf(meeting(7)))
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("重叠会议")
            model.onStartChanged(Instant.parse("2026-10-05T01:30:00Z")); model.onEndChanged(Instant.parse("2026-10-05T02:30:00Z"))
            model.saveSchedule(); advanceUntilIdle()
            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
            model.onNoteChanged("表单已修改"); advanceUntilIdle()
            assertFalse(model.uiState.value.editor.awaitingConflictConfirmation)
            model.confirmSaveDespiteConflicts(); advanceUntilIdle()
            assertEquals(1, repository.rows.value.size)
            model.saveSchedule(); advanceUntilIdle()
            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
        }
    }
    @Test fun `changed conflicting record requires fresh confirmation`() = runTest {
        val repository = Records(listOf(meeting(7)))
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("重叠会议")
            model.onStartChanged(Instant.parse("2026-10-05T01:30:00Z")); model.onEndChanged(Instant.parse("2026-10-05T02:30:00Z"))
            model.saveSchedule(); advanceUntilIdle()
            repository.rows.value = listOf(meeting(7).copy(title = "新的冲突内容"))
            model.confirmSaveDespiteConflicts(); advanceUntilIdle()
            assertEquals(1, repository.rows.value.size)
            assertTrue(model.uiState.value.editor.awaitingConflictConfirmation)
            assertTrue(assertNotNull(model.uiState.value.editor.validationMessage).contains("新的冲突内容"))
        }
    }
    @Test fun `delete reminder failure is reported as already deleted`() = runTest {
        val original = meeting(7)
        val repository = Records(listOf(original))
        withModel(repository, BrokenAlarms) { model ->
            model.openEditor(original); model.deleteSchedule(7); advanceUntilIdle()
            assertTrue(repository.rows.value.isEmpty())
            assertFalse(model.uiState.value.editor.isOpen)
            assertTrue(assertNotNull(model.uiState.value.errorMessage).contains("已删除"))
        }
    }
    @Test fun `deleting unrelated row does not discard current draft`() = runTest {
        val repository = Records(listOf(meeting(7)))
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("另一份草稿"); model.deleteSchedule(7); advanceUntilIdle()
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals("另一份草稿", model.uiState.value.editor.title)
        }
    }
    @Test fun `closed editor cannot be saved by late callbacks`() = runTest {
        val repository = Records()
        withModel(repository) { model ->
            model.openEditor(); model.closeEditor(); model.onTitleChanged("过期回调"); model.saveSchedule(); advanceUntilIdle()
            assertTrue(repository.rows.value.isEmpty())
        }
    }
    @Test fun `blank and zero reminder remain valid explicit choices`() = runTest {
        val repository = Records()
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("不提醒"); model.onReminderChanged(" ")
            model.saveSchedule(); advanceUntilIdle()
            assertNull(repository.rows.value.single().reminderMinutes)
            model.openEditor(repository.rows.value.single()); model.onReminderChanged(" 0 ")
            model.saveSchedule(); advanceUntilIdle()
            assertEquals(0, repository.rows.value.single().reminderMinutes)
        }
    }
    @Test fun `slow notification lookup cannot replace a newly opened draft`() = runTest {
        val repository = Records(listOf(meeting(7)))
        withModel(repository) { model ->
            repository.readGate = CompletableDeferred()
            model.openNotificationDetail(7); runCurrent()
            model.openEditor(); model.onTitleChanged("查询期间的草稿")
            repository.readGate!!.complete(Unit); advanceUntilIdle()
            assertEquals("查询期间的草稿", model.uiState.value.editor.title)
            assertEquals(7L, model.uiState.value.editor.pendingNotificationId)
            model.closeEditor(); advanceUntilIdle()
            assertEquals(7L, model.uiState.value.editor.editingId)
        }
    }
    @Test fun `newer notification wins over an older query after draft closes`() = runTest {
        val repository = Records(listOf(meeting(7), meeting(8).copy(title = "最新通知")))
        withModel(repository) { model ->
            repository.readGate = CompletableDeferred()
            model.openEditor(); model.openNotificationDetail(7); model.closeEditor(); runCurrent()
            model.openNotificationDetail(8); runCurrent()
            repository.readGate!!.complete(Unit); advanceUntilIdle()
            assertEquals(8L, model.uiState.value.editor.editingId)
            assertEquals("最新通知", model.uiState.value.editor.title)
            assertNull(model.uiState.value.editor.pendingNotificationId)
        }
    }
    @Test fun `successful save opens deferred notification without losing saved draft`() = runTest {
        val repository = Records(listOf(meeting(7)))
        withModel(repository) { model ->
            model.openEditor(); model.onTitleChanged("待保存草稿")
            model.onStartChanged(Instant.parse("2026-10-06T01:00:00Z"))
            model.onEndChanged(Instant.parse("2026-10-06T02:00:00Z"))
            model.openNotificationDetail(7); model.saveSchedule(); advanceUntilIdle()
            assertEquals(2, repository.rows.value.size)
            assertTrue(repository.rows.value.any { it.title == "待保存草稿" })
            assertEquals(7L, model.uiState.value.editor.editingId)
        }
    }
    @Test fun `missing notification target reports error without inventing a record`() = runTest {
        val repository = Records()
        withModel(repository) { model ->
            model.openNotificationDetail(7); advanceUntilIdle()
            assertFalse(model.uiState.value.editor.isOpen)
            assertTrue(assertNotNull(model.uiState.value.errorMessage).contains("不存在"))
            assertTrue(repository.rows.value.isEmpty())
        }
    }
    @Test fun `delete database failure retains original editor and does not cancel alarm`() = runTest {
        val original = meeting(7)
        val repository = Records(listOf(original)).apply { failDelete = true }
        var cancellations = 0
        val alarms = object : ScheduleReminderSchedulerContract {
            override fun schedule(schedule: Schedule) = Unit
            override fun cancel(scheduleId: Long) { cancellations++ }
        }
        withModel(repository, alarms) { model ->
            model.openEditor(original); model.deleteSchedule(7); advanceUntilIdle()
            assertEquals(listOf(original), repository.rows.value)
            assertTrue(model.uiState.value.editor.isOpen)
            assertFalse(model.uiState.value.editor.isSaving)
            assertNotNull(model.uiState.value.editor.validationMessage)
            assertEquals(0, cancellations)
        }
    }
    @Test fun `delete waits for in flight schedule reconciliation`() = runTest {
        val repository = Records(listOf(meeting(7)))
        withModel(repository) { model ->
            val release = CompletableDeferred<Unit>()
            val reconciliation = backgroundScope.launch {
                ScheduleOperationCoordinator.run { release.await() }
            }
            runCurrent()
            try {
                model.deleteSchedule(7); runCurrent()
                assertEquals(7L, repository.rows.value.single().id)
            } finally { release.complete(Unit) }
            reconciliation.join(); advanceUntilIdle()
            assertTrue(repository.rows.value.isEmpty())
        }
    }
    @Test fun `committed save hands editor to main before alarm side effects`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val ioExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "schedule-save-io") }
        val io = ioExecutor.asCoroutineDispatcher()
        val saved = CountDownLatch(1)
        val returnFromSave = CountDownLatch(1)
        val alarmStarted = AtomicBoolean()
        val persisted = Records(listOf(meeting(7)))
        val repository = object : ScheduleRepository by persisted {
            override suspend fun saveSchedule(schedule: Schedule): Long {
                val id = persisted.saveSchedule(schedule)
                saved.countDown()
                check(returnFromSave.await(5, TimeUnit.SECONDS))
                return id
            }
        }
        val alarms = object : ScheduleReminderSchedulerContract {
            override fun schedule(schedule: Schedule) { alarmStarted.set(true) }
            override fun cancel(scheduleId: Long) = Unit
        }
        Dispatchers.setMain(main)
        val model = ScheduleViewModel(repository, alarms, io, testGenerationAccess())
        try {
            val initialTimeout = System.nanoTime() + 5_000_000_000L
            while (!model.uiState.value.isAvailable && System.nanoTime() < initialTimeout) {
                runCurrent()
                Thread.yield()
            }
            assertTrue(model.uiState.value.isAvailable)
            model.openEditor(); model.onTitleChanged("提交中的草稿")
            model.onStartChanged(Instant.parse("2026-10-06T01:00:00Z"))
            model.onEndChanged(Instant.parse("2026-10-06T02:00:00Z"))
            model.saveSchedule()
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            model.openNotificationDetail(7); runCurrent()
            // FIFO sentinel waits for save to return/suspend; Main remains paused.
            val ioQueueReached = CountDownLatch(1)
            ioExecutor.execute { ioQueueReached.countDown() }
            returnFromSave.countDown()
            assertTrue(ioQueueReached.await(5, TimeUnit.SECONDS))
            assertFalse(alarmStarted.get(), "Main must hand off the editor before alarm side effects")
            val timeout = System.nanoTime() + 5_000_000_000L
            do { runCurrent(); if (model.uiState.value.editor.editingId == 7L) break; Thread.yield() }
            while (System.nanoTime() < timeout)
            assertEquals(7L, model.uiState.value.editor.editingId)
            assertTrue(alarmStarted.get())
            assertEquals(2, persisted.rows.value.size)
        } finally {
            returnFromSave.countDown()
            model.viewModelScope.cancel()
            io.close()
            Dispatchers.resetMain()
        }
    }
    private suspend fun TestScope.withModel(repository: Records, alarms: ScheduleReminderSchedulerContract = NoAlarms, action: suspend (ScheduleViewModel) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val model = ScheduleViewModel(repository, alarms, dispatcher, testGenerationAccess())
        try { advanceUntilIdle(); action(model) }
        finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    private object NoAlarms : ScheduleReminderSchedulerContract {
        override fun schedule(schedule: Schedule) = Unit
        override fun cancel(scheduleId: Long) = Unit
    }
    private object BrokenAlarms : ScheduleReminderSchedulerContract {
        override fun schedule(schedule: Schedule): Unit = error("alarm unavailable")
        override fun cancel(scheduleId: Long): Unit = error("alarm unavailable")
    }
    private class Records(initial: List<Schedule> = emptyList()) : ScheduleRepository {
        val rows = MutableStateFlow(initial)
        var saveGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var failSave = false
        var failDelete = false
        override fun observeSchedules() = rows
        override suspend fun getSchedules(): List<Schedule> { readGate?.await(); return rows.value }
        override suspend fun getExceptions(scheduleId: Long) = emptyList<ScheduleException>()
        override suspend fun saveException(exception: ScheduleException) = Unit
        override suspend fun saveSchedule(schedule: Schedule): Long {
            saveGate?.await(); if (failSave) error("Room unavailable")
            val id = if (schedule.id == 0L) (rows.value.maxOfOrNull { it.id } ?: 0L) + 1 else schedule.id
            rows.value = rows.value.filterNot { it.id == id } + schedule.copy(id = id)
            return id
        }
        override suspend fun deleteSchedule(id: Long) {
            if (failDelete) error("Room unavailable")
            rows.value = rows.value.filterNot { it.id == id }
        }
    }
    private fun meeting(id: Long) = Schedule(id = id, title = "已有会议", startAt = Instant.parse("2026-10-05T01:00:00Z"),
        endAt = Instant.parse("2026-10-05T02:00:00Z"), timeZone = "Asia/Shanghai")
}
