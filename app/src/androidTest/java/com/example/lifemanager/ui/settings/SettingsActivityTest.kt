package com.example.lifemanager.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Run only on an isolated/readonly device: changes settings, never business data. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var settingsModel: SettingsViewModel

    @Test fun preferencesRemainSelectedAfterActivityRecreation() {
        compose.runOnIdle { settingsModel = ViewModelProvider(compose.activity)[SettingsViewModel::class.java] }
        compose.waitUntil(5000) { model().uiState.value.isAvailable }
        val original = model().uiState.value.settings!!
        try {
            compose.onNode(hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            compose.onNodeWithText("深色").performClick()
            compose.waitUntil(5000) { model().uiState.value.settings?.theme == ThemeMode.DARK && !model().uiState.value.isSaving }
            compose.onNodeWithText("DD-MM-YYYY").performScrollTo().performClick()
            compose.waitUntil(5000) { model().uiState.value.settings?.dateFormat == DateFormat.DMY && !model().uiState.value.isSaving }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("深色").performScrollTo().assertIsSelected()
            compose.onNodeWithText("DD-MM-YYYY").performScrollTo().assertIsSelected()
            assertEquals(ThemeMode.DARK, model().uiState.value.settings!!.theme)
        } finally {
            compose.runOnIdle { model().setTheme(original.theme) }
            compose.waitUntil(5000) { !model().uiState.value.isSaving }
            compose.runOnIdle { model().setDateFormat(original.dateFormat) }
            compose.waitUntil(5000) { !model().uiState.value.isSaving }
        }
    }

    private fun model() = settingsModel
}
