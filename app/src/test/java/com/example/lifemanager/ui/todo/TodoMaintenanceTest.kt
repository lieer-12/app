package com.example.lifemanager.ui.todo

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.notification.ReminderSchedulerContract
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.PausingMainDispatcher
import com.example.lifemanager.ui.common.TestGenerations
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TodoMaintenanceTest {
    @Test fun busySaveWithTagsIsRejectedBeforeDispatchAndNeverReplayed() = runTest { scenario {
        model.openEditor(original)
        model.onTitleChanged("未保存的草稿")
        model.onTagInputChanged("工作, 私人")
        advanceUntilIdle()
        coordinator.withSession {
            model.saveTodo()
            // Release maintenance before the queued VM job gets a dispatcher turn.
        }
        advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertTrue(repository.savedTags.isEmpty())
        assertEquals("原记录", repository.todos.single().title)
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("未保存的草稿", model.uiState.value.editor.title)
        assertEquals("工作, 私人", model.uiState.value.editor.tagInput)
        model.saveTodo()
        advanceUntilIdle()
        assertEquals(1, repository.saves)
        assertEquals(listOf("工作", "私人"), repository.savedTags)
    } }

    @Test fun busyToggleIsRejectedBeforeDispatchAndNeverReplayed() = runTest { scenario {
        coordinator.withSession { model.toggleTodo(original) }
        advanceUntilIdle()
        assertEquals(0, repository.toggles)
        assertFalse(repository.todos.single().isCompleted)
        assertTrue(alarms.cancelled.isEmpty())
    } }

    @Test fun busyDeleteIsRejectedBeforeDispatchAndNeverReplayed() = runTest { scenario {
        coordinator.withSession { model.deleteTodo(original.id) }
        advanceUntilIdle()
        assertEquals(0, repository.deletes)
        assertEquals(42L, repository.todos.single().id)
        assertTrue(alarms.cancelled.isEmpty())
    } }

    @Test fun cancellingMaintenanceKeepsDraftAndItsPendingNotification() = runTest { scenario {
        repository.todos = listOf(original, Todo(id = 43, title = "通知目标"))
        repository.invalidate()
        advanceUntilIdle()
        model.openEditor(original)
        model.onTitleChanged("取消维护后保留")
        model.onTagInputChanged("未保存标签")
        model.openNotificationDetail(43)
        advanceUntilIdle()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession { ready.complete(Unit); release.await() } }
        runCurrent()
        ready.await()
        owner.cancel()
        owner.join()
        advanceUntilIdle()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("取消维护后保留", model.uiState.value.editor.title)
        assertEquals("未保存标签", model.uiState.value.editor.tagInput)
        assertEquals(43L, model.uiState.value.editor.pendingNotificationId)
        model.closeEditor()
        advanceUntilIdle()
        assertEquals(43L, model.uiState.value.editor.editingId)
    } }

    @Test fun replacementClearsOldDraftAndDeferredNotificationWithReusedIds() = runTest { scenario {
        repository.todos = listOf(original, Todo(id = 43, title = "旧通知"))
        repository.invalidate()
        advanceUntilIdle()
        model.openEditor(original)
        model.onTitleChanged("旧草稿")
        model.openNotificationDetail(43)
        advanceUntilIdle()
        replace(listOf(original.copy(title = "恢复的新记录"), Todo(id = 43, title = "恢复的新通知")))
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertNull(model.uiState.value.editor.pendingNotificationId)
        model.closeEditor()
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals("恢复的新记录", repository.todos.first().title)
    } }

    @Test fun oldSaveCallbackCannotCommitANewEditorAfterReplacement() = runTest { scenario {
        model.openEditor(original)
        val editorGeneration = model.uiState.value.editor.generation
        val oldSave = { model.saveTodo(editorGeneration) }
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        model.openEditor(repository.todos.single())
        model.onTitleChanged("新的未保存草稿")
        oldSave()
        advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertEquals("恢复的新记录", repository.todos.single().title)
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("新的未保存草稿", model.uiState.value.editor.title)
    } }

    @Test fun oldInputCallbackCannotChangeANewEditorAfterReplacement() = runTest { scenario {
        model.openEditor(original)
        val editorGeneration = model.uiState.value.editor.generation
        val oldInput = { model.onTitleChanged("旧输入", editorGeneration); model.onTagInputChanged("旧标签", editorGeneration) }
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        model.openEditor(repository.todos.single())
        oldInput()
        advanceUntilIdle()
        assertEquals("恢复的新记录", model.uiState.value.editor.title)
        assertEquals("", model.uiState.value.editor.tagInput)
    } }

    @Test fun oldCloseCallbackCannotDismissANewEditorAfterReplacement() = runTest { scenario {
        model.openEditor(original)
        val editorGeneration = model.uiState.value.editor.generation
        val oldClose = { model.closeEditor(editorGeneration) }
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        model.openEditor(repository.todos.single())
        oldClose()
        advanceUntilIdle()
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("恢复的新记录", model.uiState.value.editor.title)
    } }

    @Test fun oldListCallbacksCannotToggleDeleteOrOpenReusedId() = runTest { scenario {
        val renderedGeneration = model.uiState.value.generation
        val oldOpen = { model.openEditor(original, renderedGeneration) }
        val oldToggle = { model.toggleTodo(original, renderedGeneration) }
        val oldDelete = { model.deleteTodo(original.id, renderedGeneration) }
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        oldOpen()
        oldToggle()
        oldDelete()
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(0, repository.toggles)
        assertEquals(0, repository.deletes)
        assertEquals("恢复的新记录", repository.todos.single().title)
    } }

    @Test fun queuedSaveCapturesOldGenerationBeforeItsCoroutineLaunch() = runTest { scenario {
        model.openEditor(original)
        model.onTitleChanged("排队的旧写入")
        model.onTagInputChanged("旧标签")
        model.saveTodo()
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertTrue(repository.savedTags.isEmpty())
        assertEquals("恢复的新记录", repository.todos.single().title)
        assertFalse(model.uiState.value.editor.isOpen)
        assertNull(model.uiState.value.errorMessage)
    } }

    @Test fun queuedToggleAndDeleteCaptureOldGenerationBeforeDispatch() = runTest { scenario {
        model.toggleTodo(original)
        model.deleteTodo(original.id)
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        assertEquals(0, repository.toggles)
        assertEquals(0, repository.deletes)
        assertFalse(repository.todos.single().isCompleted)
        assertEquals("恢复的新记录", repository.todos.single().title)
        assertTrue(alarms.cancelled.isEmpty())
    } }

    @Test fun queuedNotificationDoesNotRecaptureGenerationAfterIdReplacement() = runTest { scenario {
        model.openNotificationDetail(42)
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertNull(model.uiState.value.editor.pendingNotificationId)
        assertNull(model.uiState.value.errorMessage)
    } }

    @Test fun providedOldNotificationGenerationCannotOpenOrDeferReusedIdAfterUiRefresh() = runTest { scenario {
        val originalDataGeneration = access.capture()
        assertEquals(DataGeneration(7), originalDataGeneration)
        replace(listOf(original.copy(title = "恢复的新记录")))
        advanceUntilIdle()
        assertEquals(DataGeneration(8), model.uiState.value.generation)
        assertEquals("恢复的新记录", model.uiState.value.todos.single().title)

        model.openNotificationDetail(42, originalDataGeneration)
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertNull(model.uiState.value.editor.pendingNotificationId)

        model.openEditor(repository.todos.single())
        model.onTitleChanged("新的未保存草稿")
        model.openNotificationDetail(42, originalDataGeneration)
        advanceUntilIdle()
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("新的未保存草稿", model.uiState.value.editor.title)
        assertNull(model.uiState.value.editor.pendingNotificationId)
        model.closeEditor()
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertNull(model.uiState.value.editor.pendingNotificationId)
        assertNull(model.uiState.value.errorMessage)
    } }

    @Test fun providedCurrentNotificationGenerationLoadsReplacementBeforeUiObservesCommit() = runTest { scenario {
        assertEquals(DataGeneration(7), model.uiState.value.generation)
        replace(listOf(original.copy(title = "恢复的新记录")))
        val originalDataGeneration = access.capture()
        assertEquals(DataGeneration(8), originalDataGeneration)
        // No dispatcher turn between commit and handoff: the UI still carries generation 7.
        assertEquals(DataGeneration(7), model.uiState.value.generation)
        notificationDispatcher.pauseNext = true
        model.openNotificationDetail(42, originalDataGeneration)
        assertTrue(notificationDispatcher.hasHeldPublication)
        advanceUntilIdle() // Observer and fresh snapshot finish before the held lookup starts.
        assertEquals(DataGeneration(8), model.uiState.value.generation)
        assertFalse(model.uiState.value.editor.isOpen)
        notificationDispatcher.resumeHeld()
        advanceUntilIdle()
        assertEquals(DataGeneration(8), model.uiState.value.generation)
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals(DataGeneration(8), model.uiState.value.editor.generation)
        assertEquals(42L, model.uiState.value.editor.editingId)
        assertEquals("恢复的新记录", model.uiState.value.editor.title)
        assertNull(model.uiState.value.editor.pendingNotificationId)
        assertNull(model.uiState.value.errorMessage)
    } }

    @Test fun providedCurrentNotificationSurvivesGenerationObserverArrivingAfterLookup() = runTest {
        val observations = MutableStateFlow(DataGeneration(7))
        scenario(generationObservations = observations) {
            replace(listOf(original.copy(title = "恢复的新记录")))
            val originalDataGeneration = access.capture()
            assertEquals(DataGeneration(8), originalDataGeneration)
            assertEquals(DataGeneration(7), model.uiState.value.generation)
            model.openNotificationDetail(42, originalDataGeneration)
            advanceUntilIdle() // Lookup and Main delivery run while the observer still emits 7.
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(8), model.uiState.value.editor.generation)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertEquals("恢复的新记录", model.uiState.value.editor.title)

            observations.value = DataGeneration(8)
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(8), model.uiState.value.editor.generation)
            assertEquals(42L, model.uiState.value.editor.editingId)
            assertEquals("恢复的新记录", model.uiState.value.editor.title)
            assertNull(model.uiState.value.editor.pendingNotificationId)
            assertNull(model.uiState.value.errorMessage)
        }
    }

    @Test fun intermediateObservationCannotCancelAQueuedNewerGenerationNotification() = runTest {
        val observations = MutableStateFlow(DataGeneration(7))
        scenario(generationObservations = observations) {
            replace(listOf(original.copy(title = "中间记录")))
            replace(listOf(original.copy(title = "最新记录")))
            val originalDataGeneration = access.capture()
            assertEquals(DataGeneration(9), originalDataGeneration)
            assertEquals(DataGeneration(7), model.uiState.value.generation)
            notificationDispatcher.pauseNext = true
            model.openNotificationDetail(42, originalDataGeneration)
            assertTrue(notificationDispatcher.hasHeldPublication)
            observations.value = DataGeneration(8)
            advanceUntilIdle() // Intermediate context arrives while the generation-9 lookup is held.
            notificationDispatcher.resumeHeld()
            advanceUntilIdle()
            observations.value = DataGeneration(9)
            advanceUntilIdle()
            assertEquals(DataGeneration(9), model.uiState.value.generation)
            assertTrue(model.uiState.value.editor.isOpen)
            assertEquals(DataGeneration(9), model.uiState.value.editor.generation)
            assertEquals("最新记录", model.uiState.value.editor.title)
            assertNull(model.uiState.value.editor.pendingNotificationId)
        }
    }

    @Test fun admittedSaveDrainsThroughMainHandoffAndReminderSideEffects() = runTest { scenario {
        repository.saveRelease = CompletableDeferred()
        model.openEditor(original)
        model.onTitleChanged("已经准入的保存")
        model.onDueAtChanged(Instant.parse("2027-01-01T12:00:00Z"))
        model.saveTodo()
        runCurrent()
        assertEquals(1, repository.saves)
        model.saveTodo()
        val finish = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession {
            finish.await()
        } }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            repository.saveRelease!!.complete(Unit)
            runCurrent()
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals(listOf(42L), alarms.scheduled)
            assertEquals(1, repository.saves)
            assertEquals("已经准入的保存", repository.todos.single().title)
        } finally {
            repository.saveRelease!!.complete(Unit)
            finish.complete(Unit)
            owner.cancel()
            owner.join()
        }
    } }

    @Test fun admittedNotificationLookupDrainsBeforeMaintenanceCanReplaceItsTarget() = runTest { scenario {
        repository.lookupRelease = CompletableDeferred()
        model.openNotificationDetail(42)
        runCurrent()
        assertTrue(repository.lookupEntered.isCompleted)
        val finish = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession { finish.await() } }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            repository.lookupRelease!!.complete(Unit)
            runCurrent()
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            assertEquals("原记录", model.uiState.value.editor.title)
        } finally {
            repository.lookupRelease!!.complete(Unit)
            finish.complete(Unit)
            owner.cancel()
            owner.join()
        }
    } }

    @Test fun metadataFailurePreventsSaveAndTagWritesWithoutInventingGenerationZero() = runTest { scenario {
        model.openEditor(original)
        model.onTitleChanged("元数据故障时不能保存")
        model.onTagInputChanged("不能落库的标签")
        generations.currentFailure = IllegalStateException("synthetic missing metadata")
        model.saveTodo()
        advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertTrue(repository.savedTags.isEmpty())
        assertEquals("原记录", repository.todos.single().title)
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.editor.isSaving)
        assertFalse(model.uiState.value.isAvailable)
        assertEquals(DataGeneration(7), model.uiState.value.generation)
        assertTrue(model.uiState.value.errorMessage != null)
    } }

    @Test fun replacementRefreshesActualColdQueriesWithoutBusinessFlowEmission() = runTest { scenario {
        val todoReads = repository.coldTodoReads
        val tagReads = repository.coldTagReads
        replace(listOf(original.copy(title = "冷查询的新记录")))
        advanceUntilIdle()
        assertEquals("冷查询的新记录", model.uiState.value.todos.single().title)
        assertTrue(repository.coldTodoReads > todoReads)
        assertTrue(repository.coldTagReads > tagReads)
    } }

    @Test fun bufferedInvalidationCannotPublishItsStaleDtoAsCurrentData() = runTest { scenario {
        replace(listOf(original.copy(title = "数据库当前记录")))
        advanceUntilIdle()
        repository.invalidate(listOf(original.copy(title = "缓冲的旧记录")))
        advanceUntilIdle()
        assertEquals("数据库当前记录", model.uiState.value.todos.single().title)
        assertEquals(1, model.uiState.value.stats.pendingCount)
    } }

    @Test fun maintenanceCancellationResumesFreshReadsAfterInvalidationDuringFreeze() = runTest { scenario {
        val readsBefore = repository.coldTodoReads
        coordinator.withSession {
            repository.todos = listOf(original.copy(title = "维护取消后的当前记录"))
            repository.invalidate(listOf(original.copy(title = "陈旧失效通知")))
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals("维护取消后的当前记录", model.uiState.value.todos.single().title)
        assertTrue(repository.coldTodoReads > readsBefore)
        repository.todos = listOf(original.copy(title = "仍然观察后续更新"))
        repository.invalidate()
        advanceUntilIdle()
        assertEquals("仍然观察后续更新", model.uiState.value.todos.single().title)
    } }

    @Test fun suspendedColdQueryResumesFreshReadsAfterMaintenanceCancellation() = runTest { scenario {
        repository.coldReadRelease = CompletableDeferred()
        repository.invalidate(listOf(original.copy(title = "仅作失效通知")))
        runCurrent()
        assertTrue(repository.coldReadEntered.isCompleted)
        val finish = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession { finish.await() } }
        try {
            runCurrent()
            // An observation may cancel its read when maintenance starts; either
            // cancellation or draining must release it and allow a fresh read at IDLE.
            repository.coldReadRelease!!.complete(Unit)
            runCurrent()
            assertEquals(MaintenanceState.READY, coordinator.state.value)
        } finally {
            repository.coldReadRelease!!.complete(Unit)
            finish.complete(Unit)
            owner.cancel()
            owner.join()
        }
        advanceUntilIdle()
        assertEquals("原记录", model.uiState.value.todos.single().title)
    } }

    @Test fun delayedMetadataFaultCannotPreventSavingFreshEditorAfterReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val fixture = Fixture()
        fixture.model = TodoViewModel(fixture.repository, fixture.alarms, dispatcher, fixture.access)
        with(fixture) {
            try {
                advanceUntilIdle()
                model.openEditor(original)
                model.onTitleChanged("旧故障草稿")
                generations.currentFailure = IllegalStateException("synthetic old metadata fault")
                main.pauseNext = true
                model.saveTodo()
                advanceUntilIdle()
                assertTrue(main.hasHeldPublication)
                generations.currentFailure = null
                replace(listOf(original.copy(title = "恢复的新记录")))
                advanceUntilIdle()
                model.openEditor(repository.todos.single())
                model.onTitleChanged("新世代仍然可以保存")
                main.resumeHeld()
                advanceUntilIdle()
                model.saveTodo()
                advanceUntilIdle()
                assertEquals(1, repository.saves)
                assertEquals("新世代仍然可以保存", repository.todos.single().title)
                assertFalse(model.uiState.value.editor.isOpen)
                assertNull(model.uiState.value.errorMessage)
            } finally {
                main.resumeHeld()
                model.viewModelScope.cancel()
                advanceUntilIdle()
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun coldStartNotificationArrivingBeforeSnapshotIsNotLost() = runTest { scenario(loadInitially = false) {
        model.openNotificationDetail(42)
        advanceUntilIdle()
        assertEquals(42L, model.uiState.value.editor.editingId)
        assertEquals("原记录", model.uiState.value.editor.title)
    } }

    @Test fun coldStartNotificationBirthCannotRetagReusedIdBeforeInitialSnapshotLoads() = runTest { scenario(loadInitially = false) {
        assertNull(model.uiState.value.generation)
        model.openNotificationDetail(42)
        replace(listOf(original.copy(title = "恢复后的同 ID 记录")))
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertNull(model.uiState.value.editor.pendingNotificationId)
        assertEquals("恢复后的同 ID 记录", model.uiState.value.todos.single().title)
        assertNull(model.uiState.value.errorMessage)
    } }

    @Test fun successfulSaveDrainsDeferredNotificationWithoutNestedAdmission() = runTest { scenario {
        repository.todos = listOf(original, Todo(id = 43, title = "保存后通知"))
        repository.invalidate()
        advanceUntilIdle()
        model.openEditor(original)
        model.onTitleChanged("保存成功")
        model.openNotificationDetail(43)
        model.saveTodo()
        advanceUntilIdle()
        assertEquals("保存成功", repository.todos.first { it.id == 42L }.title)
        assertEquals(43L, model.uiState.value.editor.editingId)
        assertNull(model.uiState.value.errorMessage)
    } }

    @Test fun olderSaveCleanupCannotUnlockAndDuplicateANewerDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val fixture = Fixture()
        fixture.model = TodoViewModel(fixture.repository, fixture.alarms, dispatcher, fixture.access)
        with(fixture) {
            try {
                advanceUntilIdle()
                model.openEditor(original)
                model.onTitleChanged("保存 A")
                // A has closed its committed editor. Hold only its final Main cleanup,
                // after reminder work, while B can start its own admitted write.
                alarms.afterCancel = { alarms.afterCancel = null; main.pauseNext = true }
                model.saveTodo()
                advanceUntilIdle()
                assertTrue(main.hasHeldPublication)
                assertFalse(model.uiState.value.editor.isOpen)

                repository.saveRelease = CompletableDeferred()
                model.openEditor()
                model.onTitleChanged("新建 B")
                model.saveTodo()
                runCurrent()
                assertEquals(2, repository.saves)
                assertTrue(model.uiState.value.editor.isSaving)
                main.resumeHeld()
                runCurrent()
                val remainedSaving = model.uiState.value.editor.isSaving
                model.saveTodo()
                runCurrent()
                repository.saveRelease!!.complete(Unit)
                advanceUntilIdle()

                assertEquals(2, repository.saves, "A cleanup allowed B to be inserted twice")
                assertEquals(1, repository.todos.count { it.title == "新建 B" })
                assertTrue(remainedSaving, "A cleanup reset B's saving flag")
                assertFalse(model.uiState.value.editor.isOpen)
            } finally {
                repository.saveRelease?.complete(Unit)
                main.resumeHeld()
                model.viewModelScope.cancel()
                advanceUntilIdle()
                Dispatchers.resetMain()
            }
        }
    }

    private suspend fun TestScope.scenario(
        loadInitially: Boolean = true,
        generationObservations: Flow<DataGeneration>? = null,
        body: suspend Fixture.() -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = Fixture(generationObservations)
        fixture.notificationDispatcher = PausingMainDispatcher(dispatcher)
        fixture.model = TodoViewModel(fixture.repository, fixture.alarms, fixture.notificationDispatcher, fixture.access)
        try {
            if (loadInitially) advanceUntilIdle()
            fixture.body()
        } finally {
            fixture.repository.saveRelease?.complete(Unit)
            fixture.repository.lookupRelease?.complete(Unit)
            fixture.repository.coldReadRelease?.complete(Unit)
            fixture.model.viewModelScope.cancel()
            fixture.notificationDispatcher.resumeHeld()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(generationObservations: Flow<DataGeneration>? = null) {
        val original = Todo(id = 42, title = "原记录")
        val generations = TestGenerations(7)
        private val generationRepository = object : DataGenerationRepository by generations {
            override fun observe() = generationObservations ?: generations.observe()
        }
        val coordinator = MaintenanceCoordinator(generationRepository)
        val access = GenerationAccess(generationRepository, coordinator)
        val repository = Repository(listOf(original))
        val alarms = Alarms()
        lateinit var model: TodoViewModel
        lateinit var notificationDispatcher: PausingMainDispatcher

        suspend fun replace(values: List<Todo>) {
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) { repository.todos = values }
            } }
        }
    }

    private class Repository(initial: List<Todo>) : TodoRepository {
        var todos = initial
        private val invalidations = MutableStateFlow(initial)
        var coldTodoReads = 0
        var coldTagReads = 0
        var saves = 0
        private var nextId = 99L
        var toggles = 0
        var deletes = 0
        var savedTags = emptyList<String>()
        var saveRelease: CompletableDeferred<Unit>? = null
        var lookupRelease: CompletableDeferred<Unit>? = null
        val lookupEntered = CompletableDeferred<Unit>()
        var coldReadRelease: CompletableDeferred<Unit>? = null
        val coldReadEntered = CompletableDeferred<Unit>()

        fun invalidate(buffered: List<Todo> = todos) { invalidations.value = buffered }

        override fun observeTodos(filter: TodoFilter) = flow {
            coldTodoReads++
            if (coldReadRelease != null) {
                coldReadEntered.complete(Unit)
                coldReadRelease!!.await()
            }
            emit(todos.filter { filter.query.isBlank() || it.title.contains(filter.query) })
            invalidations.collect { buffered ->
                emit(buffered.filter { filter.query.isBlank() || it.title.contains(filter.query) })
            }
        }

        override fun observeTags() = flow {
            coldTagReads++
            emit(emptyList<Tag>())
            invalidations.collect { emit(emptyList<Tag>()) }
        }

        override suspend fun getAllTodos(): List<Todo> {
            val snapshot = todos
            lookupEntered.complete(Unit)
            lookupRelease?.await()
            return snapshot
        }

        override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long {
            saves++
            saveRelease?.await()
            savedTags = tagNames
            val saved = todo.copy(id = if (todo.id == 0L) nextId++ else todo.id, tagNames = tagNames)
            todos = todos.filterNot { it.id == saved.id } + saved
            invalidate()
            return saved.id
        }

        override suspend fun deleteTodo(id: Long) {
            deletes++
            todos = todos.filterNot { it.id == id }
            invalidate()
        }

        override suspend fun setCompleted(id: Long, completed: Boolean) {
            toggles++
            todos = todos.map { if (it.id == id) it.copy(isCompleted = completed) else it }
            invalidate()
        }
    }

    private class Alarms : ReminderSchedulerContract {
        val scheduled = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        var afterCancel: (() -> Unit)? = null
        override fun schedule(todoId: Long, title: String, dueAt: Instant) { scheduled += todoId }
        override fun cancel(todoId: Long) { cancelled += todoId; afterCancel?.invoke() }
    }
}
