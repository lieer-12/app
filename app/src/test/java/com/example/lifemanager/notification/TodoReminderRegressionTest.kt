package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import com.example.lifemanager.MainActivity
import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TodoReminderRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun changingFutureDeadlineToPastCancelsExistingAlarm() {
        val scheduler = ReminderScheduler(context)
        scheduler.schedule(42, "待办", Instant.now().plusSeconds(3600))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        scheduler.schedule(42, "待办", Instant.now().minusSeconds(1))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun legacyBroadcastWithoutCurrentDeadlineDoesNotPostNotification() {
        TodoReminderReceiver().onReceive(context, Intent()
            .putExtra(TodoReminderReceiver.EXTRA_TODO_ID, 42L)
            .putExtra(TodoReminderReceiver.EXTRA_TITLE, "已删除的旧标题"))
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }

    @Test fun notificationClickReusesActivitySoDraftViewModelIsPreserved() {
        NotificationHelper.showTodoReminder(context, 42, "待办")
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertEquals(42L, intent.getLongExtra("todo_id", 0))
    }

    @Test fun postingAfterUpgradeReplacesLegacyNotificationPendingIntent() {
        val legacy = PendingIntent.getActivity(context, ReminderKey.forTodo(42),
            Intent(context, MainActivity::class.java).putExtra("todo_id", 42L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationHelper.showTodoReminder(context, 42, "新标题")
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(shadowOf(legacy).isCanceled)
    }

    @Test fun upgradedSchedulerCancelsLegacyBroadcastAlarm() {
        val legacy = PendingIntent.getBroadcast(context, ReminderKey.forTodo(42),
            Intent(context, TodoReminderReceiver::class.java).putExtra("todo_id", 42L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3600000, legacy)
        ReminderScheduler(context).schedule(42, "待办", Instant.now().minusSeconds(1))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        assertTrue(shadowOf(legacy).isCanceled)
    }
}
