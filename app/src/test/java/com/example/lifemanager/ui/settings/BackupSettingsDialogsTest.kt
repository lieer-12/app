package com.example.lifemanager.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BackupSettingsDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewReportsRealCountsAndDoesNotInvokeFinalConfirmation() {
        var first: Pair<String, Boolean?>? = null
        var finals = 0
        compose.setContent { MaterialTheme {
            BackupSettingsDialogs(BackupSettingsUiState("original-operation", BackupAction.RESTORE, BackupStep.RESTORE_PREVIEW,
                rowCounts = mapOf("todos" to 42, "habit_records" to 7), sourceDate = "2026-10-05T00:00:00Z"),
                { id, choice -> first = id to choice }, { _, _ -> finals++ })
        } }
        compose.onNodeWithText("待办：42").assertExists()
        compose.onNodeWithText("打卡记录：7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("同意并备份当前数据").performClick()
        assertEquals("original-operation" to true, first)
        assertEquals(0, finals)
    }

    @Test fun separateFinalConfirmationUsesOriginalOperationAndIsAccessibleInDarkLargeFont() {
        var final: Pair<String, Boolean>? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    BackupSettingsDialogs(BackupSettingsUiState("original-operation", BackupAction.CLEAR, BackupStep.FINAL_CONFIRM),
                        { _, _ -> error("Preview consent must not be reused") }, { id, answer -> final = id to answer })
                }
            }
        }
        compose.onNodeWithText("确认清空业务数据").assertIsDisplayed().performClick()
        assertEquals("original-operation" to true, final)
        compose.onNodeWithText("取消，保留原数据").assertIsDisplayed().performClick()
        assertEquals("original-operation" to false, final)
    }
}
