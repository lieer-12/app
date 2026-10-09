package com.example.lifemanager.ui.settings

import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lifemanager.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Real system OpenDocument/CreateDocument cancellation only. Never creates/deletes app business rows. */
@RunWith(AndroidJUnit4::class)
class BackupPickerActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cancelledSystemSourcePickerKeepsSettingsAndReturnsToUsableActions() {
        openSettings()
        compose.onNodeWithText("从备份恢复").performScrollTo().performClick()
        cancelSystemPicker()
        compose.onNodeWithText("已取消").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("从备份恢复").performScrollTo().assertIsEnabled()
    }

    @Test fun exportShowsPrivacyConsentThenLaunchesRealSystemDestinationPicker() {
        openSettings()
        compose.onNodeWithText("导出完整备份").performScrollTo().performClick()
        compose.onNodeWithText("导出明文备份").assertIsDisplayed()
        compose.onNodeWithText("选择保存位置").performClick()
        cancelSystemPicker()
        compose.onNodeWithText("已取消").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导出完整备份").performScrollTo().assertIsEnabled()
    }

    private fun openSettings() {
        compose.waitUntil(5_000) { ViewModelProvider(compose.activity)[SettingsViewModel::class.java].uiState.value.isAvailable }
        compose.onNode(hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
    }
    private fun cancelSystemPicker() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.nanoTime() + 10_000_000_000L
        var pickerVisible = false
        while (!pickerVisible && System.nanoTime() < deadline) {
            compose.waitForIdle() // Drain IO-triggered recomposition and launcher LaunchedEffect.
            pickerVisible = automation.rootInActiveWindow?.packageName?.toString()?.let { it.endsWith("documentsui") } == true
            if (!pickerVisible) Thread.sleep(50)
        }
        assertTrue(pickerVisible, "Actual Android system document picker must have appeared")
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已取消").fetchSemanticsNodes().isNotEmpty() }
    }
}
