package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.*
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSettingsViewModelTest {
    @Test fun togglesPersistIndependentFieldsAndReconcileOutsideAdmission() = scenario {
        model.setReminderEnabled("todo", false); advanceUntilIdle()
        assertFalse(preferences.values.value.todoReminders)
        assertTrue(preferences.values.value.scheduleReminders)
        assertTrue(preferences.values.value.subscriptionReminders)
        model.setReminderEnabled("schedule", false); advanceUntilIdle()
        model.setReminderEnabled("subscription", false); advanceUntilIdle()
        assertEquals(3, effects.reconciled)
        assertEquals(3, effects.queued)
        model.setReminderEnabled("todo", true); advanceUntilIdle()
        assertTrue(preferences.values.value.todoReminders)
        assertFalse(preferences.values.value.scheduleReminders)
        assertFalse(preferences.values.value.subscriptionReminders)
    }
    @Test fun committedPreferenceSurvivesCalibrationFailureWithExplicitStatus() = scenario {
        effects.fail = true
        model.setReminderEnabled("schedule", false); advanceUntilIdle()
        assertFalse(preferences.values.value.scheduleReminders)
        assertTrue(model.uiState.value.errorMessage!!.contains("偏好已保存"))
        assertEquals(1, effects.queued)
    }
    @Test fun failedPreferenceWriteDoesNotClaimSuccessOrChangeState() = scenario {
        preferences.fail = true
        model.setReminderEnabled("todo", false); advanceUntilIdle()
        assertTrue(preferences.values.value.todoReminders)
        assertTrue(model.uiState.value.errorMessage!!.contains("保存设置失败"))
        assertEquals(0, effects.reconciled)
    }
    @Test fun delayedOldToggleCannotChangeReplacementPreferences() = scenario {
        val old = model.uiState.value.generation
        generations.commit(DataGeneration(0)) {}
        advanceUntilIdle()
        model.setReminderEnabled("todo", false, old); advanceUntilIdle()
        assertTrue(preferences.values.value.todoReminders)
        assertEquals(0, effects.reconciled)
    }
    private fun scenario(block: suspend Fixture.() -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(this, dispatcher)
        try { advanceUntilIdle(); f.block() } finally { f.model.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
    private class Fixture(val scope: TestScope, dispatcher: CoroutineDispatcher) {
        val generations = TestGenerations()
        val coordinator = MaintenanceCoordinator(generations)
        val preferences = Preferences()
        val effects = Effects(coordinator)
        val model = SettingsViewModel(preferences, dispatcher, GenerationAccess(generations, coordinator)).apply { reminderEffects = effects }
        fun advanceUntilIdle() = scope.testScheduler.advanceUntilIdle()
    }
    private class Preferences : SettingsRepository {
        val values = MutableStateFlow(AppSettings())
        var fail = false
        override fun observeSettings() = values
        override suspend fun getSettings() = values.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { if (fail) error("synthetic write failure"); values.value = transform(values.value) }
    }
    private class Effects(val coordinator: MaintenanceCoordinator) : MaintenanceReminderEffects {
        var reconciled = 0; var queued = 0; var fail = false
        override suspend fun clearPrevious(identities: ReminderIdentities) = Unit
        override suspend fun reconcile() {
            coordinator.capture() // Nested admission would throw if VM kept its save permit.
            reconciled++; if (fail) error("synthetic calibration failure")
        }
        override fun requestReconciliation() { queued++ }
    }
}
