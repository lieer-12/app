package com.example.lifemanager.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.BuildConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Standalone host: does not change app settings, system permissions, or business data. */
@RunWith(AndroidJUnit4::class)
class SettingsPlatformSectionAccessibilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkThemeAtDoubleFontScaleKeepsActionsAndOfflineLicenseReaderUsable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                            SettingsPlatformSection()
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("系统通知设置").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTextNotClipped("系统通知设置")
        val version = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
        compose.onNodeWithText(version).performScrollTo().assertIsDisplayed()
        assertTextNotClipped(version)
        compose.onNodeWithText("查看离线第三方许可证").performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Apache License 2.0 原文").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Apache License 2.0 原文").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("END OF TERMS AND CONDITIONS", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The end must be reachable, not merely present in a clipped, unscrollable dialog.
        compose.onNodeWithText("END OF TERMS AND CONDITIONS", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回依赖清单").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("关闭许可证").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("关闭许可证").assertDoesNotExist()
        compose.onNodeWithText("查看离线第三方许可证").assertIsDisplayed()
    }

    private fun assertTextNotClipped(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertFalse(it.hasVisualOverflow,
            "Clipped at 200% font scale: $label; size=${it.size}; paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}; overflowWidth=${it.didOverflowWidth}; overflowHeight=${it.didOverflowHeight}; constraints=${it.layoutInput.constraints}; style=${it.layoutInput.style}") }
    }
}
