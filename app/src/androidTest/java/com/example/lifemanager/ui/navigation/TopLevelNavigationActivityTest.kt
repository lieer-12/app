package com.example.lifemanager.ui.navigation

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.MainActivity
import com.example.lifemanager.notification.ReminderGeneration
import com.example.lifemanager.notification.TodoReminderEntryPoint
import com.example.lifemanager.ui.settings.SettingsViewModel
import com.example.lifemanager.ui.settings.BackupSettingsViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Owned isolated emulator only. Unsaved drafts, no business inserts or database resets. */
@RunWith(AndroidJUnit4::class)
class TopLevelNavigationActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // Removing top-level pop/save/restore must replay old tab visits instead of returning to root.
    @Test fun repeatedTabCyclesReturnToTodoInsteadOfReplayingVisitHistory() {
        repeat(3) { listOf("待办", "日程", "打卡", "订阅", "设置").forEach(::selectTab) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitSelectedTab("待办")
        compose.onNodeWithContentDescription("添加待办").assertIsDisplayed()
    }

    // Creating a new Habit owner rather than restoring its saved entry loses the selected subpage.
    @Test fun habitSubpageSurvivesRepeatedSwitchesAndActivityRecreation() {
        selectTab("打卡")
        compose.onNode(hasText("统计") and tabRole).performClick()
        repeat(3) { selectTab("订阅"); selectTab("打卡") }
        compose.onNode(hasText("统计") and tabRole).assertIsSelected()
        compose.activityRule.scenario.recreate()
        awaitSelectedTab("打卡")
        compose.onNode(hasText("统计") and tabRole).assertIsSelected()
    }

    // Notification navigation must save the outgoing entry, not strand the draft in an older owner.
    @Test fun habitDraftSurvivesNotificationDepartureAndTabReturn() {
        selectTab("打卡")
        awaitAction("添加习惯")
        compose.onNodeWithContentDescription("添加习惯").performClick()
        compose.onNodeWithText("名称").performTextInput("未保存的导航习惯草稿")
        sendNotification(MainActivity.EXTRA_TODO_ID)
        awaitSelectedTab("待办")
        selectTab("打卡")
        compose.onNodeWithText("未保存的导航习惯草稿").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("未保存的导航习惯草稿").assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun subscriptionDraftSurvivesNotificationDepartureAndTabReturn() {
        selectTab("订阅")
        awaitAction("添加订阅")
        compose.onNodeWithContentDescription("添加订阅").performClick()
        compose.onNodeWithText("订阅名称").performTextInput("未保存的导航订阅草稿")
        sendNotification(MainActivity.EXTRA_SCHEDULE_ID)
        awaitSelectedTab("日程")
        selectTab("订阅")
        compose.onNodeWithText("未保存的导航订阅草稿").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("未保存的导航订阅草稿").assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun maintenanceCannotRestoreATabOrAdmitANewNotification(): Unit = runBlocking {
        selectTab("打卡")
        compose.onNode(hasText("统计") and tabRole).performClick()
        selectTab("设置")
        val coordinator = EntryPointAccessors.fromApplication(
            compose.activity.applicationContext, TodoReminderEntryPoint::class.java,
        ).maintenanceCoordinator()
        coordinator.withSession {
            compose.waitUntil(5_000) {
                compose.onAllNodes(bottomTab("待办") and isNotEnabled()).fetchSemanticsNodes().size == 1
            }
            listOf("待办", "日程", "打卡", "订阅", "设置").forEach {
                compose.onNode(bottomTab(it)).assertIsNotEnabled()
            }
            sendNotification(MainActivity.EXTRA_TODO_ID)
            awaitSelectedTab("设置")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(bottomTab("打卡") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        selectTab("打卡")
        compose.onNode(hasText("统计") and tabRole).assertIsSelected()
    }

    @Test fun settingsReturnButtonIsLockedDuringMaintenance(): Unit = runBlocking {
        selectTab("设置")
        val coordinator = EntryPointAccessors.fromApplication(
            compose.activity.applicationContext, TodoReminderEntryPoint::class.java,
        ).maintenanceCoordinator()
        coordinator.withSession {
            awaitMaintenanceLock()
            compose.onNodeWithText("返回待办").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithText("返回待办").performScrollTo().assertIsEnabled().performClick()
        awaitSelectedTab("待办")
    }

    @Test fun systemBackCannotLeaveAFrozenTopLevelPage(): Unit = runBlocking {
        val coordinator = EntryPointAccessors.fromApplication(
            compose.activity.applicationContext, TodoReminderEntryPoint::class.java,
        ).maintenanceCoordinator()
        listOf("日程", "打卡", "订阅", "设置", "待办").forEach { label ->
            selectTab(label)
            coordinator.withSession {
                awaitMaintenanceLock()
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                awaitSelectedTab(label)
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(bottomTab("待办") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            if (label != "待办") {
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                awaitSelectedTab("待办")
            }
        }
    }

    private fun awaitMaintenanceLock() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(bottomTab("待办") and isNotEnabled()).fetchSemanticsNodes().size == 1
        }
    }

    // An unguarded toolbar shortcut bypasses the same freeze that disables the bottom tabs.
    @Test fun todoSettingsShortcutIsLockedDuringMaintenance(): Unit = runBlocking {
        selectTab("待办")
        val shortcut = hasText("设置") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val coordinator = EntryPointAccessors.fromApplication(
            compose.activity.applicationContext, TodoReminderEntryPoint::class.java,
        ).maintenanceCoordinator()
        coordinator.withSession {
            awaitMaintenanceLock()
            compose.onNode(shortcut).assertIsNotEnabled().performClick()
            awaitSelectedTab("待办")
        }
        compose.onNode(shortcut).assertIsEnabled().performClick()
        awaitSelectedTab("设置")
    }

    // Leaving Settings removes its consent/cancellation UI and strands a still-busy backup owner.
    @Test fun todoNotificationWaitsForBackupConsentToFinish() =
        notificationWaitsForBackupConsent(MainActivity.EXTRA_TODO_ID, "待办")

    @Test fun scheduleNotificationWaitsForBackupConsentToFinish() =
        notificationWaitsForBackupConsent(MainActivity.EXTRA_SCHEDULE_ID, "日程")

    @Test fun subscriptionNotificationWaitsForBackupConsentToFinish() =
        notificationWaitsForBackupConsent(MainActivity.EXTRA_SUBSCRIPTION_ID, "订阅")

    private fun notificationWaitsForBackupConsent(extra: String, target: String) {
        selectTab("设置")
        compose.onNodeWithText("导出完整备份").performScrollTo().performClick()
        compose.onNodeWithText("导出明文备份").assertIsDisplayed()
        val backup = ViewModelProvider(compose.activity)[BackupSettingsViewModel::class.java]
        try {
            sendNotification(extra)
            compose.waitUntil(5_000) {
                val owner = ViewModelProvider(compose.activity)
                val pending = when (extra) {
                    MainActivity.EXTRA_TODO_ID -> owner[TodoNavigationViewModel::class.java].pending.value
                    MainActivity.EXTRA_SCHEDULE_ID -> owner[ScheduleNavigationViewModel::class.java].pending.value
                    MainActivity.EXTRA_SUBSCRIPTION_ID -> owner[SubscriptionNavigationViewModel::class.java].pending.value
                    else -> error("Unexpected test notification")
                }
                pending != null || compose.onAllNodesWithText("导出明文备份").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("导出明文备份").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            awaitSelectedTab(target)
            compose.waitUntil(5_000) { !backup.uiState.value.busy }
        } finally {
            compose.runOnIdle { backup.uiState.value.operationId?.let(backup::cancel) }
        }
    }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    // On this portrait device Subscription also has an inner tab called 订阅. Match the bottom row,
    // not the content tab; NavigationBarItem clears its child icon semantics in the merged tree.
    private fun bottomTab(label: String) = hasText(label) and tabRole and SemanticsMatcher("bottom navigation row") {
        it.boundsInRoot.center.y > compose.activity.resources.displayMetrics.heightPixels * 0.75f
    }

    private fun selectTab(label: String) {
        compose.onNode(bottomTab(label)).performClick()
        awaitSelectedTab(label)
    }

    private fun awaitSelectedTab(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(bottomTab(label) and isSelected()).fetchSemanticsNodes().size == 1
        }
    }

    private fun awaitAction(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(label) and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }

    private fun sendNotification(extra: String) {
        compose.runOnIdle {
            val activity = compose.activity
            activity.startActivity(Intent(activity.intent)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ReminderGeneration.EXTRA,
                    requireNotNull(ViewModelProvider(activity)[SettingsViewModel::class.java].uiState.value.generation).value)
                .putExtra(extra, Long.MAX_VALUE))
        }
        compose.waitForIdle()
    }
}
