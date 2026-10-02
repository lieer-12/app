package com.example.lifemanager.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.ui.theme.LifeManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeSelectionShowsSelectedStateAndChangesThroughItsCallback() {
        val state = mutableStateOf(SettingsUiState(settings = AppSettings(), isLoading = false, isAvailable = true))
        compose.setContent { LifeManagerTheme {
            SettingsContent(state.value, onThemeChanged = { theme ->
                state.value = state.value.copy(settings = state.value.settings!!.copy(theme = theme))
            }, onRetry = {}, onBack = {})
        } }
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithText("浅色").assertIsNotSelected()
    }

    @Test fun savingDisablesThemeControls() {
        compose.setContent { LifeManagerTheme {
            SettingsContent(SettingsUiState(settings = AppSettings(), isLoading = false, isAvailable = true, isSaving = true), {}, {}, {})
        } }
        compose.onNodeWithText("深色").assertIsNotEnabled()
    }

    @Test fun unreadSettingsShowRetryInsteadOfAnEditableDefault() {
        compose.setContent { LifeManagerTheme {
            SettingsContent(SettingsUiState(isLoading = false, errorMessage = "读取设置失败，请重试"), {}, {}, {})
        } }
        compose.onNodeWithText("重新读取设置").assertExists()
        compose.onNodeWithText("深色").assertDoesNotExist()
    }

    @Test fun failedSaveOffersReloadRatherThanPromisingToResubmitTheSelection() {
        var reloads = 0
        compose.setContent { LifeManagerTheme {
            SettingsContent(SettingsUiState(settings = AppSettings(), isLoading = false,
                isAvailable = true, errorMessage = "保存设置失败，请重新读取后再次选择"),
                {}, { reloads++ }, {})
        } }
        compose.onNodeWithText("重新读取设置").performClick()
        kotlin.test.assertEquals(1, reloads)
        compose.onNodeWithText("跟随系统").assertIsSelected()
    }

    @Test fun datePreferenceSelectionIsAccessibleAndUsesItsSaveCallback() {
        val state = mutableStateOf(SettingsUiState(settings = AppSettings(), isLoading = false, isAvailable = true))
        compose.setContent { LifeManagerTheme {
            SettingsContent(state.value, {}, {}, {}, onDateFormatChanged = { format ->
                state.value = state.value.copy(settings = state.value.settings!!.copy(dateFormat = format))
            })
        } }
        compose.onNodeWithText("DD-MM-YYYY").performScrollTo().performClick()
        compose.onNodeWithText("DD-MM-YYYY").assertIsSelected()
    }

    @Test fun currencyEditorPassesTheEnteredCodeToTheSaveAction() {
        var requested: String? = null
        compose.setContent { LifeManagerTheme {
            SettingsContent(SettingsUiState(settings = AppSettings(), isLoading = false, isAvailable = true),
                {}, {}, {}, onCurrencyChanged = { requested = it })
        } }
        compose.onNodeWithText("新订阅默认币种（ISO 4217）").performScrollTo().performTextReplacement("EUR")
        compose.onNodeWithText("保存默认币种").performScrollTo().performClick()
        kotlin.test.assertEquals("EUR", requested)
    }

    @Test fun reminderSelectionKeepsOtherSelectedDays() {
        val state = mutableStateOf(SettingsUiState(settings = AppSettings(defaultReminderDays = setOf(3)),
            isLoading = false, isAvailable = true))
        compose.setContent { LifeManagerTheme {
            SettingsContent(state.value, {}, {}, {}, onReminderDayChanged = { day, selected ->
                val current = state.value.settings!!
                state.value = state.value.copy(settings = current.copy(defaultReminderDays =
                    if (selected) current.defaultReminderDays + day else current.defaultReminderDays - day))
            })
        } }
        compose.onNodeWithText("提前 1 天").performScrollTo().performClick()
        kotlin.test.assertEquals(setOf(1, 3), state.value.settings!!.defaultReminderDays)
        compose.onNodeWithText("提前 3 天").performClick()
        kotlin.test.assertEquals(setOf(1), state.value.settings!!.defaultReminderDays)
    }

    @Test fun aSecondAcceptedReminderChangePreservesTheCommittedFirstDayBeforeObservationCatchesUp() {
        val repository = DelayedObservationPreferences()
        val model = SettingsViewModel(repository, Dispatchers.IO)
        try {
            compose.setContent { LifeManagerTheme { SettingsScreen({}, model) } }
            compose.waitUntil(5000) { model.uiState.value.isAvailable }
            compose.onNodeWithText("提前 1 天").performScrollTo().performClick()
            compose.waitUntil(5000) { !model.uiState.value.isSaving && 1 in repository.saved.value.defaultReminderDays }
            kotlin.test.assertEquals(emptySet(), model.uiState.value.settings!!.defaultReminderDays)
            compose.onNodeWithText("提前 7 天").performScrollTo().performClick()
            compose.waitUntil(5000) { !model.uiState.value.isSaving && 7 in repository.saved.value.defaultReminderDays }
            kotlin.test.assertEquals(setOf(1, 7), repository.saved.value.defaultReminderDays)
        } finally { model.viewModelScope.cancel() }
    }

    private class DelayedObservationPreferences : SettingsRepository {
        val saved = MutableStateFlow(AppSettings())
        private val observed = MutableStateFlow(AppSettings())
        override fun observeSettings() = observed
        override suspend fun getSettings() = saved.value
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { saved.value = transform(saved.value) }
    }
}
