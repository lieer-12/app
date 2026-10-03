package com.example.lifemanager.ui.habit

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.ui.common.PausingMainDispatcher
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HabitMaintenanceTest {
    @Test fun briefBusyReadDoesNotTerminateInitialOrSubsequentObservation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = TestGenerations(7)
        store.nextCurrentFailures.addLast(MaintenanceBusyException())
        val repo = Repository()
        val model = HabitViewModel(repo, dispatcher, GenerationAccess(store, MaintenanceCoordinator(store)))
        try {
            advanceUntilIdle()
            assertTrue(model.uiState.value.isAvailable)
            repo.habits.value = listOf(repo.habits.value.single().copy(name = "仍然观察"))
            advanceUntilIdle()
            assertEquals("仍然观察", model.uiState.value.habits.single().name)
        } finally { model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun delayedMetadataErrorCannotDisableFreshPageAfterReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val store = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(store)
        val repo = Repository()
        val model = HabitViewModel(repo, dispatcher, GenerationAccess(store, coordinator))
        try {
            advanceUntilIdle()
            model.openEditor(repo.habits.value.single())
            store.currentFailure = IllegalStateException("synthetic old fault")
            main.pauseNext = true
            model.saveHabit()
            advanceUntilIdle()
            assertTrue(main.hasHeldPublication)
            store.currentFailure = null
            coordinator.withSession { session -> coordinator.withMaintenance(session) { store.commit(session.generation) {} } }
            advanceUntilIdle()
            assertEquals(DataGeneration(8), model.uiState.value.generation)
            assertTrue(model.uiState.value.isAvailable)
            main.resumeHeld()
            advanceUntilIdle()
            assertTrue(model.uiState.value.isAvailable)
            assertEquals(null, model.uiState.value.errorMessage)
        } finally { model.viewModelScope.cancel(); main.resumeHeld(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun replacementAfterTemporaryGenerationReadFailureStillInvalidatesOldDraft() = runTest { scenario { model, repo, store, coordinator ->
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("读取故障前的草稿")
        store.currentFailure = IllegalStateException("synthetic temporary failure")
        model.saveHabit()
        advanceUntilIdle()
        store.currentFailure = null
        coordinator.withSession { session -> coordinator.withMaintenance(session) { store.commit(session.generation) {} } }
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(DataGeneration(8), model.uiState.value.generation)
    } }

    @Test fun oldSaveCallbackCannotSaveANewEditorOpenedAfterReplacement() = runTest { scenario { model, repo, store, coordinator ->
        model.openEditor(repo.habits.value.single())
        val oldToken = model.uiState.value.editor.generation
        coordinator.withSession { session -> coordinator.withMaintenance(session) { store.commit(session.generation) {} } }
        advanceUntilIdle()
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("新的草稿")
        model.saveHabit(oldToken)
        advanceUntilIdle()
        assertEquals(0, repo.saves)
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("新的草稿", model.uiState.value.editor.name)
    } }

    @Test fun oldInputAndDismissCannotChangeANewEditorAfterReplacement() = runTest { scenario { model, repo, store, coordinator ->
        model.openEditor(repo.habits.value.single())
        val oldToken = model.uiState.value.editor.generation
        coordinator.withSession { session -> coordinator.withMaintenance(session) { store.commit(session.generation) {} } }
        advanceUntilIdle()
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("旧输入回调", oldToken)
        model.closeEditor(oldToken)
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals("原记录", model.uiState.value.editor.name)
    } }

    @Test fun generationReadFailureFailsClosedWithoutEscapingWriteCoroutine() = runTest { scenario { model, repo, store, _ ->
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("不能写入")
        store.currentFailure = IllegalStateException("synthetic missing metadata")
        model.saveHabit()
        advanceUntilIdle()
        assertEquals(0, repo.saves)
        assertFalse(model.uiState.value.isAvailable)
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.editor.isSaving)
    } }

    @Test fun cancellingMaintenancePreservesDraftAndNeverQueuesBusySave() = runTest { scenario { model, repo, _, coordinator ->
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("未保存的草稿")
        advanceUntilIdle()
        coordinator.withSession {
            runCurrent()
            assertTrue(model.uiState.value.isMaintaining)
            model.saveHabit()
            // Do not dispatch the VM job until maintenance has released admission.
        }
        advanceUntilIdle()
        assertEquals(0, repo.saves)
        assertEquals("未保存的草稿", model.uiState.value.editor.name)
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.isMaintaining)
        model.saveHabit()
        advanceUntilIdle()
        assertEquals(1, repo.saves)
        assertEquals("未保存的草稿", repo.habits.value.single().name)
    } }

    @Test fun replacementInvalidatesOldDraftAndCallbacksEvenWhenIdIsReused() = runTest { scenario { model, repo, store, coordinator ->
        val old = repo.habits.value.single()
        val token = model.uiState.value.generation
        model.openEditor(old)
        model.onNameChanged("旧草稿")
        advanceUntilIdle()
        coordinator.withSession { session -> coordinator.withMaintenance(session) {
            store.commit(session.generation) { repo.habits.value = listOf(old.copy(name = "恢复的新记录")) }
        } }
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(DataGeneration(8), model.uiState.value.generation)
        model.openEditor(old, token)
        model.deleteHabit(old.id, token)
        model.toggleToday(old.id, token)
        advanceUntilIdle()
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(0, repo.deletes)
        assertEquals(0, repo.toggles)
        assertEquals("恢复的新记录", repo.habits.value.single().name)
    } }

    @Test fun delayedSaveCannotWriteAfterReplacementCommit() = runTest { scenario { model, repo, store, coordinator ->
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("延迟的旧写入")
        model.saveHabit() // queued on the dispatcher, not admitted yet
        coordinator.withSession { session -> coordinator.withMaintenance(session) {
            store.commit(session.generation) { repo.habits.value = listOf(repo.habits.value.single().copy(name = "新记录")) }
        } }
        advanceUntilIdle()
        assertEquals(0, repo.saves)
        assertEquals("新记录", repo.habits.value.single().name)
        assertFalse(model.uiState.value.editor.isOpen)
        assertEquals(null, model.uiState.value.errorMessage)
    } }

    @Test fun admittedSaveDrainsIncludingResultAndRejectsDuplicateSave() = runTest { scenario { model, repo, _, coordinator ->
        repo.saveRelease = CompletableDeferred()
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("写入完成")
        model.saveHabit()
        runCurrent()
        model.saveHabit()
        model.closeEditor()
        runCurrent()
        assertTrue(model.uiState.value.editor.isOpen)
        assertEquals(1, repo.saves)
        val finish = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession {
            assertFalse(model.uiState.value.editor.isOpen)
            finish.await()
        } }
        runCurrent()
        assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
        repo.saveRelease!!.complete(Unit)
        runCurrent()
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        assertEquals(1, repo.saves)
        finish.complete(Unit)
        owner.join()
    } }

    @Test fun deletedRecordCannotBeRecreatedByExistingEditorSave() = runTest { scenario { model, repo, _, _ ->
        model.openEditor(repo.habits.value.single())
        model.onNameChanged("不能复活")
        repo.habits.value = emptyList()
        advanceUntilIdle()
        model.saveHabit()
        advanceUntilIdle()
        assertEquals(0, repo.saves)
        assertTrue(repo.habits.value.isEmpty())
        assertTrue(model.uiState.value.editor.isOpen)
        assertFalse(model.uiState.value.editor.isSaving)
    } }

    private suspend fun TestScope.scenario(body: suspend TestScope.(HabitViewModel, Repository, TestGenerations, MaintenanceCoordinator) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(store)
        val repo = Repository()
        val model = HabitViewModel(repo, dispatcher, GenerationAccess(store, coordinator))
        try { advanceUntilIdle(); body(model, repo, store, coordinator) }
        finally { model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private class Repository : HabitRepository {
        val habits = MutableStateFlow(listOf(Habit(id = 42, name = "原记录", startDate = LocalDate.now())))
        val records = MutableStateFlow<List<HabitRecord>>(emptyList())
        var saves = 0
        var deletes = 0
        var toggles = 0
        var saveRelease: CompletableDeferred<Unit>? = null
        override fun observeHabits() = habits
        override fun observeRecords(start: LocalDate, end: LocalDate) = records
        override suspend fun saveHabit(habit: Habit): Long {
            saves++
            saveRelease?.await()
            habits.value = listOf(habit.copy(id = 42))
            return 42
        }
        override suspend fun deleteHabit(id: Long) { deletes++; habits.value = emptyList() }
        override suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean { toggles++; return true }
    }
}
