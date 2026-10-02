package com.example.lifemanager.ui.schedule

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.ui.navigation.ScheduleNavigationViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated test device only. Uses unsaved drafts; never inserts Schedule rows. */
@RunWith(AndroidJUnit4::class)
class ScheduleNotificationActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun returningFromAnotherModuleAndRepeatedTapPreserveDraft() {
        openDraft()
        sendNotification(MainActivity.EXTRA_SUBSCRIPTION_ID)
        compose.waitUntil(5000) { compose.onAllNodesWithText("订阅费用").fetchSemanticsNodes().isNotEmpty() }
        sendNotification(MainActivity.EXTRA_SCHEDULE_ID)
        assertWaitingDraft()
        val firstToken = assertNotNull(editorToken())
        assertConsumed()
        sendNotification(MainActivity.EXTRA_SCHEDULE_ID)
        compose.waitUntil(5000) { (editorToken() ?: 0L) > firstToken }
        assertWaitingDraft()
        assertConsumed()
    }

    @Test fun recreationDoesNotReplayConsumedIntentOrDiscardDeferredNotification() {
        openDraft()
        sendNotification(MainActivity.EXTRA_SCHEDULE_ID)
        assertWaitingDraft()
        val token = assertNotNull(editorToken())
        assertConsumed()
        compose.activityRule.scenario.recreate()
        assertWaitingDraft()
        assertEquals(token, editorToken())
        assertConsumed()
    }

    private fun openDraft() {
        compose.onNode(hasText("日程") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithContentDescription("添加日程").performClick()
        compose.onNodeWithText("标题").performTextInput("仅用于测试的未保存日程草稿")
    }

    private fun sendNotification(extra: String) {
        compose.runOnIdle {
            val activity = compose.activity
            activity.startActivity(Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(extra, Long.MAX_VALUE))
        }
        compose.waitForIdle()
    }

    private fun assertWaitingDraft() {
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("通知日程等待查看；保存或关闭当前草稿后打开").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("仅用于测试的未保存日程草稿").assertExists()
    }

    private fun editorToken(): Long? = compose.runOnIdle {
        ViewModelProvider(compose.activity)[ScheduleViewModel::class.java].uiState.value.editor.pendingNotificationToken
    }

    private fun assertConsumed() = compose.runOnIdle {
        assertNull(ViewModelProvider(compose.activity)[ScheduleNavigationViewModel::class.java].pending.value)
        assertEquals(false, compose.activity.intent.hasExtra(MainActivity.EXTRA_SCHEDULE_ID))
    }
}
