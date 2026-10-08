package com.example.lifemanager.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleReminderAdmissionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    // Current payloads still require admission before Room qualification.
    @Test fun readyMaintenanceRejectsSchedulePosting(): Unit = runTest {
        val admission = ReminderAdmissionFixture()
        admission.coordinator.withSession {
            assertEquals(MaintenanceState.READY, admission.coordinator.state.value)
            admission.scheduleReceiver(StandardTestDispatcher(testScheduler)).onReceive(context, validIntent())
            runCurrent()
            assertEquals(0, notifications.size(), "Schedule must not post while maintenance is READY")
            assertEquals(1, admission.calibrationRequests)
        }
        runCurrent()
        assertEquals(0, notifications.size(), "Rejected schedule must not replay after maintenance")
        assertEquals(1, admission.calibrationRequests)
    }

    @Test fun cancelledMaintenanceDoesNotReplayScheduleButAllowsAFreshArrival(): Unit = runTest {
        val admission = ReminderAdmissionFixture()
        assertFailsWith<CancellationException> {
            admission.coordinator.withSession {
                admission.scheduleReceiver(StandardTestDispatcher(testScheduler)).onReceive(context, validIntent())
                throw CancellationException("synthetic confirmation cancellation")
            }
        }
        assertEquals(MaintenanceState.IDLE, admission.coordinator.state.value)
        assertEquals(DataGeneration(37), admission.generations.current())
        runCurrent()
        assertEquals(0, notifications.size(), "Maintenance arrival must not replay after cancellation")
        assertEquals(1, admission.calibrationRequests)

        admission.scheduleReceiver(StandardTestDispatcher(testScheduler)).onReceive(context, validIntent())
        runCurrent()
        assertEquals(1, notifications.size())
        assertEquals("schedule fixture", notifications.allNotifications.single().extras.getCharSequence("android.text"))
        assertEquals(1, admission.calibrationRequests)
    }

    @Test fun scheduleBeforeGenerationCommitCannotPostAfterDelayedIO(): Unit = runTest {
        val admission = ReminderAdmissionFixture()
        admission.scheduleReceiver(StandardTestDispatcher(testScheduler)).onReceive(context, validIntent())
        assertEquals(0, notifications.size())
        admission.coordinator.withSession { session ->
            admission.coordinator.withMaintenance(session) { admission.generations.commit(session.generation) {} }
        }
        runCurrent()
        assertEquals(0, notifications.size())
        assertEquals(0, admission.calibrationRequests)
    }

    // Matches the explicit current schedule fixture, but its title remains untrusted.
    private fun validIntent() = Intent().setData(android.net.Uri.parse("lifemanager://schedule-reminder/41"))
        .putExtra(ReminderGeneration.EXTRA, 37L)
        .putExtra(ScheduleReminderReceiver.EXTRA_OCCURRENCE_START, ReminderTestSchedules.start.toEpochMilli())
        .putExtra(ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, 41L)
        .putExtra(ScheduleReminderReceiver.EXTRA_TITLE, "schedule fixture")
}
