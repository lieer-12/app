package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import com.example.lifemanager.ui.common.PausingMainDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMaintenanceTest {
    @Test fun briefBusyReadDoesNotTerminateInitialOrSubsequentObservation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = TestGenerations()
        store.nextCurrentFailures.addLast(MaintenanceBusyException())
        val repository = Preferences()
        val model = SettingsViewModel(repository, dispatcher, GenerationAccess(store, MaintenanceCoordinator(store)))
        try {
            advanceUntilIdle()
            assertTrue(model.uiState.value.isAvailable)
            repository.saved.value = AppSettings(theme = ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, model.uiState.value.settings!!.theme)
        } finally { model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun delayedMetadataErrorCannotStopFreshObservationBeforeReplacementSnapshotIsPublished() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val store = TestGenerations()
        val coordinator = MaintenanceCoordinator(store)
        val repository = Preferences()
        val model = SettingsViewModel(repository, dispatcher, GenerationAccess(store, coordinator))
        try {
            advanceUntilIdle()
            store.currentFailure = IllegalStateException("synthetic old fault")
            main.pauseNext = true
            model.setTheme(ThemeMode.DARK)
            advanceUntilIdle()
            assertTrue(main.hasHeldPublication)
            store.currentFailure = null
            repository.observationGate = CompletableDeferred()
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                store.commit(session.generation) { repository.saved.value = AppSettings(theme = ThemeMode.LIGHT) }
            } }
            advanceUntilIdle() // New generation has been observed; the first fresh prefs are still gated.
            main.resumeHeld()
            advanceUntilIdle()
            repository.observationGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(DataGeneration(1), model.uiState.value.generation)
            assertTrue(model.uiState.value.isAvailable)
            assertEquals(ThemeMode.LIGHT, model.uiState.value.settings!!.theme)
            assertNull(model.uiState.value.errorMessage)
        } finally { model.viewModelScope.cancel(); main.resumeHeld(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun generationReadFailureDuringSaveFailsClosedAndRetryRecoversWithoutWritingOldSelection() = scenario { model, repository, generations, _ ->
        advanceUntilIdle()
        generations.currentFailure = IllegalStateException("synthetic missing metadata")
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(0, repository.writes)
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.settings!!.theme)
        assertFalse(model.uiState.value.isAvailable)
        assertFalse(model.uiState.value.isSaving)
        assertNotNull(model.uiState.value.errorMessage)
        model.retry()
        advanceUntilIdle()
        assertFalse(model.uiState.value.isAvailable)
        assertFalse(model.uiState.value.isLoading)
        assertEquals(0, repository.writes)
        generations.currentFailure = null
        model.retry()
        advanceUntilIdle()
        assertTrue(model.uiState.value.isAvailable)
        assertNull(model.uiState.value.errorMessage)
        assertEquals(DataGeneration(0), model.uiState.value.generation)
        model.setTheme(ThemeMode.LIGHT)
        advanceUntilIdle()
        assertEquals(1, repository.writes)
        assertEquals(ThemeMode.LIGHT, repository.saved.value.theme)
    }

    @Test fun failedInitialGenerationObservationResubscribesOnRetryUsingActualGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = Preferences()
        val generations = FailingObservationGenerations()
        val coordinator = MaintenanceCoordinator(generations)
        val model = SettingsViewModel(repository, dispatcher, GenerationAccess(generations, coordinator))
        try {
            advanceUntilIdle()
            assertFalse(model.uiState.value.isAvailable)
            assertFalse(model.uiState.value.isLoading)
            assertNull(model.uiState.value.settings)
            assertNotNull(model.uiState.value.errorMessage)
            model.setTheme(ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(0, repository.writes)
            generations.observeFailure = null
            model.retry()
            advanceUntilIdle()
            assertTrue(model.uiState.value.isAvailable)
            assertEquals(DataGeneration(19), model.uiState.value.generation)
            assertNull(model.uiState.value.errorMessage)
            model.setTheme(ThemeMode.LIGHT)
            advanceUntilIdle()
            assertEquals(ThemeMode.LIGHT, repository.saved.value.theme)
        } finally {
            model.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun freezeRejectsAllPreferenceEventsSynchronouslyAndCancellationResumesEditing() = scenario { model, repository, generations, coordinator ->
        advanceUntilIdle()
        val rendered = model.uiState.value.generation
        val freeze = launch { coordinator.withSession { awaitCancellation() } }
        runCurrent()
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        assertTrue(model.uiState.value.isMaintaining)
        model.setTheme(ThemeMode.DARK, rendered)
        model.setDateFormat(DateFormat.DMY, rendered)
        model.setDefaultCurrency("EUR", rendered)
        model.setDefaultReminderDay(7, true, rendered)
        model.retry(rendered)
        // Release before any launched work can execute: busy events must never queue.
        freeze.cancel()
        advanceUntilIdle()
        assertEquals(AppSettings(), repository.saved.value)
        assertEquals(0, repository.writes)
        assertEquals(DataGeneration(0), generations.current())
        assertFalse(model.uiState.value.isMaintaining)
        model.setTheme(ThemeMode.DARK, rendered)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, repository.saved.value.theme)
    }

    @Test fun committedGenerationRejectsOldRenderedCallbackEvenAfterFreshSettingsArePublished() = scenario { model, repository, generations, coordinator ->
        advanceUntilIdle()
        val rendered = model.uiState.value.generation
        coordinator.withSession { session -> coordinator.withMaintenance(session) {
            generations.commit(session.generation) {
                repository.saved.value = AppSettings(theme = ThemeMode.LIGHT)
            }
        } }
        advanceUntilIdle()
        assertEquals(DataGeneration(1), model.uiState.value.generation)
        assertEquals(ThemeMode.LIGHT, model.uiState.value.settings!!.theme)
        model.setTheme(ThemeMode.DARK, rendered)
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, repository.saved.value.theme)
        assertEquals(0, repository.writes)
        assertFalse(model.uiState.value.isSaving)
    }

    @Test fun delayedLaunchedWriteChecksOldGenerationBeforeTouchingRepository() = scenario { model, repository, generations, coordinator ->
        advanceUntilIdle()
        model.setTheme(ThemeMode.DARK)
        assertTrue(model.uiState.value.isSaving)
        // StandardTestDispatcher has not entered the write coroutine yet.
        coordinator.withSession { session -> coordinator.withMaintenance(session) {
            generations.commit(session.generation) {
                repository.saved.value = AppSettings(theme = ThemeMode.LIGHT)
            }
        } }
        advanceUntilIdle()
        assertEquals(0, repository.writes)
        assertEquals(ThemeMode.LIGHT, repository.saved.value.theme)
        assertEquals(DataGeneration(1), model.uiState.value.generation)
        assertFalse(model.uiState.value.isSaving)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun observerValuesAreOnlyInvalidationsAndFreshColdReadProvidesPublishedSnapshot() = scenario { model, repository, _, _ ->
        advanceUntilIdle()
        // Existing observer is allowed to emit a buffered, obsolete value.
        repository.coldSnapshot = AppSettings(defaultCurrency = "EUR")
        repository.saved.value = AppSettings(defaultCurrency = "USD")
        advanceUntilIdle()
        assertEquals("EUR", model.uiState.value.settings!!.defaultCurrency)
        assertEquals("USD", repository.getSettings().defaultCurrency)
    }

    @Test fun maintenanceDrainsAnAdmittedSaveAndPublishesBeforeAcquiringSession() = scenario { model, repository, _, coordinator ->
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.writeGate = gate
        model.setTheme(ThemeMode.DARK)
        runCurrent()
        var displayAtReady: ThemeMode? = null
        val freeze = launch { coordinator.withSession {
            displayAtReady = model.uiState.value.settings!!.theme
        } }
        runCurrent()
        assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        freeze.join()
        assertEquals(ThemeMode.DARK, displayAtReady)
        assertFalse(model.uiState.value.isSaving)
    }

    private fun scenario(block: suspend TestScope.(SettingsViewModel, Preferences, TestGenerations, MaintenanceCoordinator) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = Preferences()
        val generations = TestGenerations()
        val coordinator = MaintenanceCoordinator(generations)
        val model = SettingsViewModel(repository, dispatcher, GenerationAccess(generations, coordinator))
        try { block(model, repository, generations, coordinator) }
        finally { model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private class FailingObservationGenerations(private val store: TestGenerations = TestGenerations(19)) : DataGenerationRepository by store {
        var observeFailure: Exception? = IllegalStateException("synthetic generation observer failure")
        override fun observe() = flow {
            observeFailure?.let { throw it }
            emitAll(store.observe())
        }
    }

    private class Preferences : SettingsRepository {
        val saved = MutableStateFlow(AppSettings())
        var coldSnapshot: AppSettings? = null
        var observations = 0
        var writes = 0
        var writeGate: CompletableDeferred<Unit>? = null
        var observationGate: CompletableDeferred<Unit>? = null
        override fun observeSettings() = flow {
            observationGate?.await()
            observations++
            val snapshot = coldSnapshot
            if (snapshot != null) emit(snapshot) else emitAll(saved)
        }
        override suspend fun getSettings() = saved.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
            writes++
            writeGate?.await()
            saved.value = transform(saved.value)
        }
    }
}
