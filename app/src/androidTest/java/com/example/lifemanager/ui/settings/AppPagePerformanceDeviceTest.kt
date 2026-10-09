package com.example.lifemanager.ui.settings

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.unit.dp

/** Smoke timings include instrumentation/idleness overhead; not frame rate or macrobenchmark claims. */
@RunWith(AndroidJUnit4::class)
class AppPagePerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun businessAndSettingsPagesReachUsableControlsWithoutNetwork() {
        listOf("日程" to "添加日程", "打卡" to "添加习惯", "订阅" to "添加订阅", "待办" to "添加待办").forEach { (tab, action) ->
            val begin = SystemClock.elapsedRealtime()
            compose.onNode(hasText(tab) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(action) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            Log.i("Phase5Performance", "$tab usable-control-ms=${SystemClock.elapsedRealtime() - begin}")
            compose.onNodeWithContentDescription(action).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        val begin = SystemClock.elapsedRealtime()
        compose.onNode(hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("深色").assertIsEnabled()
        Log.i("Phase5Performance", "设置 usable-control-ms=${SystemClock.elapsedRealtime() - begin}")
    }
}
