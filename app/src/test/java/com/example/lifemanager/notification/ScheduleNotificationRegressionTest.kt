package com.example.lifemanager.notification

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import com.example.lifemanager.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ScheduleNotificationRegressionTest {
    @Test fun notificationReplacesLegacyClickIntentAndReusesActivity() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = PendingIntent.getActivity(context, ReminderKey.forSchedule(42),
            Intent(context, MainActivity::class.java).putExtra("schedule_id", 42L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationHelper.showScheduleReminder(context, 42, "会议", com.example.lifemanager.domain.maintenance.DataGeneration(37))
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(42L, intent.getLongExtra("schedule_id", 0))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(shadowOf(legacy).isCanceled)
    }
}
