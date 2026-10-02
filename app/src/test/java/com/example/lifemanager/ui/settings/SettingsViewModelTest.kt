package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test fun preferencesChangeOnlyTheirOwnFields() = scenario { model, repository ->
        repository.values.value = AppSettings(theme = ThemeMode.DARK)
        advanceUntilIdle()
        model.setDateFormat(DateFormat.DMY)
        advanceUntilIdle()
        model.setDefaultCurrency(" eur ")
        advanceUntilIdle()
        model.setDefaultReminderDay(1, true)
        advanceUntilIdle()
        model.setDefaultReminderDay(7, true)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, model.uiState.value.settings!!.theme)
        assertEquals(DateFormat.DMY, repository.values.value.dateFormat)
        assertEquals("EUR", repository.values.value.defaultCurrency)
        assertEquals(setOf(1, 7), repository.values.value.defaultReminderDays)
    }

    @Test fun invalidDefaultPreferencesCannotOverwriteSavedValues() = scenario { model, repository ->
        advanceUntilIdle()
        model.setDefaultCurrency("INVALID")
        advanceUntilIdle()
        assertEquals("CNY", repository.values.value.defaultCurrency)
        assertNotNull(model.uiState.value.errorMessage)
        model.setDefaultReminderDay(2, true)
        advanceUntilIdle()
        assertEquals(emptySet(), repository.values.value.defaultReminderDays)
        assertNotNull(model.uiState.value.errorMessage)
    }
    @Test fun loadsActualSavedThemeRatherThanReplacingItWithDefaults() = scenario { model, repository ->
        repository.values.value = AppSettings(theme = ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, model.uiState.value.settings!!.theme)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun savingThemeUpdatesPersistedAndDisplayedState() = scenario { model, repository ->
        advanceUntilIdle()
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, repository.values.value.theme)
        assertEquals(ThemeMode.DARK, model.uiState.value.settings!!.theme)
        assertFalse(model.uiState.value.isSaving)
    }

    @Test fun failedWriteKeepsSavedThemeAndShowsAnError() = scenario { model, repository ->
        advanceUntilIdle()
        repository.failWrites = true
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.settings!!.theme)
        assertEquals(ThemeMode.SYSTEM, repository.values.value.theme)
        assertNotNull(model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isSaving)
    }

    @Test fun readFailureBlocksUpdatesUntilSuccessfulRetry() = scenario { model, repository ->
        repository.failReads = true
        advanceUntilIdle()
        assertNull(model.uiState.value.settings)
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, repository.values.value.theme)
        repository.failReads = false
        model.retry()
        advanceUntilIdle()
        assertNotNull(model.uiState.value.settings)
        assertNull(model.uiState.value.errorMessage)
    }

    @Test fun failedSaveCanReloadAndThenSaveASelectionAgain() = scenario { model, repository ->
        advanceUntilIdle()
        repository.failWrites = true
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        repository.failWrites = false
        model.retry()
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.settings!!.theme)
        assertNull(model.uiState.value.errorMessage)
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, model.uiState.value.settings!!.theme)
    }

    @Test fun duplicateUpdateWhileSavingCannotOverrideFirstSelection() = scenario { model, repository ->
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.writeGate = gate
        model.setTheme(ThemeMode.DARK)
        runCurrent()
        assertTrue(model.uiState.value.isSaving)
        model.setTheme(ThemeMode.LIGHT)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, repository.values.value.theme)
    }

    @Test fun failedRefreshKeepsLastDisplayButBlocksWritesFromStaleSettings() = scenario { model, repository ->
        advanceUntilIdle()
        repository.failReads = true
        model.retry()
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.settings!!.theme)
        model.setTheme(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, repository.values.value.theme)
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test fun observerFailureDuringASaveCannotReenableEditingWhenTheSaveFinishes() = scenario { model, repository ->
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.writeGate = gate
        model.setTheme(ThemeMode.DARK)
        runCurrent()
        repository.failReads = true
        runCurrent()
        assertFalse(model.uiState.value.isAvailable)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, repository.values.value.theme)
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.settings!!.theme)
        assertFalse(model.uiState.value.isAvailable)
        assertFalse(model.uiState.value.isSaving)
        model.setTheme(ThemeMode.LIGHT)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, repository.values.value.theme)
        repository.failReads = false
        model.retry()
        advanceUntilIdle()
        assertTrue(model.uiState.value.isAvailable)
        assertEquals(ThemeMode.DARK, model.uiState.value.settings!!.theme)
    }

    @Test fun cancellingTheOwnerBeforeAWriteCommitsDoesNotReportSuccessOrSaveTheSelection() = scenario { model, repository ->
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.writeGate = gate
        model.setTheme(ThemeMode.DARK)
        runCurrent()
        model.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, repository.values.value.theme)
        assertFalse(model.uiState.value.isSaving)
        assertNull(model.uiState.value.errorMessage)
    }

    private fun scenario(block: suspend TestScope.(SettingsViewModel, Preferences) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = Preferences()
        val model = SettingsViewModel(repository, dispatcher)
        try { block(model, repository) } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private class Preferences : SettingsRepository {
        val values = MutableStateFlow(AppSettings())
        private val readFailure = MutableStateFlow(false)
        var failReads: Boolean
            get() = readFailure.value
            set(value) { readFailure.value = value }
        var failWrites = false
        var writeGate: CompletableDeferred<Unit>? = null
        override fun observeSettings() = readFailure.flatMapLatest { fail ->
            flow { if (fail) throw IOException("read failed"); emitAll(values) }
        }
        override suspend fun getSettings() = values.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
            writeGate?.await()
            if (failWrites) throw IOException("write failed")
            values.value = transform(values.value)
        }
    }
}
