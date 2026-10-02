package com.example.lifemanager.ui.todo

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.example.lifemanager.MainActivity
import com.example.lifemanager.ui.navigation.TodoNavigationViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** Run on an isolated test device. These cases keep unsaved drafts and never write Todo rows. */
@RunWith(AndroidJUnit4::class)
class TodoNotificationActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun notificationReturningFromAnotherModulePreservesDraft() {
        openDraft()
        sendNotification(MainActivity.EXTRA_SUBSCRIPTION_ID)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("订阅费用").fetchSemanticsNodes().isNotEmpty()
        }
        sendNotification(MainActivity.EXTRA_TODO_ID)
        assertWaitingDraft()
        val firstToken = assertNotNull(editorToken())
        assertNavigationConsumed()
        // Repeated taps are new events, but must still not overwrite an existing draft.
        sendNotification(MainActivity.EXTRA_TODO_ID)
        compose.waitUntil(5000) { (editorToken() ?: 0L) > firstToken }
        assertWaitingDraft()
        assertNavigationConsumed()
    }

    @Test fun activityRecreationRetainsDraftAndDeferredNotification() {
        openDraft()
        sendNotification(MainActivity.EXTRA_TODO_ID)
        assertWaitingDraft()
        val token = assertNotNull(editorToken())
        assertNavigationConsumed()
        compose.activityRule.scenario.recreate()
        assertWaitingDraft()
        assertEquals(token, editorToken(), "Recreation must not replay the consumed notification")
        assertNavigationConsumed()
    }

    private fun openDraft() {
        compose.onNodeWithContentDescription("添加待办").performClick()
        compose.onNodeWithText("标题").performTextInput("仅用于测试的未保存草稿")
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
            compose.onAllNodesWithText("通知待办等待查看；保存或关闭当前草稿后打开").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("仅用于测试的未保存草稿").assertExists()
    }

    private fun editorToken(): Long? = compose.runOnIdle {
        ViewModelProvider(compose.activity)[TodoViewModel::class.java].uiState.value.editor.pendingNotificationToken
    }

    private fun assertNavigationConsumed() = compose.runOnIdle {
        assertNull(ViewModelProvider(compose.activity)[TodoNavigationViewModel::class.java].pending.value)
    }
}
