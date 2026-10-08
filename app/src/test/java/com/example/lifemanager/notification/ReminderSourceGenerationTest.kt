package com.example.lifemanager.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import com.example.lifemanager.domain.maintenance.DataGeneration
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReminderSourceGenerationTest {
    @Test fun missingOrMalformedPayloadNeverBecomesGenerationZero() {
        assertNull(ReminderGeneration.read(Intent()))
        assertNull(ReminderGeneration.read(Intent().putExtra(ReminderGeneration.EXTRA, -1L)))
        assertNull(ReminderGeneration.read(Intent().putExtra(ReminderGeneration.EXTRA, "37")))
        assertEquals(DataGeneration(0), ReminderGeneration.read(Intent().putExtra(ReminderGeneration.EXTRA, 0L)))
    }

    @Test fun oldPayloadArrivingAfterCommitCannotAcquireNewGeneration(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        fixture.generations.commit(DataGeneration(37)) {}
        var posted = 0
        var finished = 0
        ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
            .launch({ finished++ }, {}, DataGeneration(37)) { posted++ }.join()
        assertEquals(0, posted)
        assertEquals(1, finished)
    }

    @Test fun missingSourceIsRejectedAndRequestsFreshCalibration(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        var posted = 0
        var calibrations = 0
        var finished = 0
        ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
            .launch({ finished++ }, { calibrations++ }, null) { posted++ }.join()
        assertEquals(0, posted)
        assertEquals(1, calibrations)
        assertEquals(1, finished)
    }

    @Test fun clickKeepsPostingGenerationAndCleanupPreservesCurrentNotification() {
        val context = RuntimeEnvironment.getApplication()
        NotificationHelper.showTodoReminder(context, 41, "old", DataGeneration(37))
        NotificationHelper.showScheduleReminder(context, 42, "current", DataGeneration(38))
        val manager = context.getSystemService(NotificationManager::class.java)
        val old = shadowOf(manager).allNotifications.first { it.extras.getCharSequence("android.text") == "old" }
        assertEquals(DataGeneration(37), ReminderGeneration.read(shadowOf(old.contentIntent).savedIntent))
        ReminderNotifications(context).clearStale(DataGeneration(38))
        assertEquals(listOf("current"), shadowOf(manager).allNotifications.map { it.extras.getCharSequence("android.text").toString() })
    }
}
