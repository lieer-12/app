package com.example.lifemanager.notification

import android.app.Application
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderBroadcastRunnerAdmissionTest {
    // A deadline around only IO delivery would strand both PendingResult and capture admission.
    @Test fun stalledGenerationCaptureTimesOutAtEightSecondsAndReleasesMaintenance(): Unit = runTest {
        val values = ReminderAdmissionGenerations()
        val readEntered = CompletableDeferred<Unit>()
        val stalledRead = CompletableDeferred<Unit>()
        var blockReads = true
        var readCancelled = false
        val generations = object : DataGenerationRepository by values {
            override suspend fun current(): DataGeneration {
                if (blockReads) {
                    readEntered.complete(Unit)
                    try {
                        stalledRead.await()
                    } finally {
                        readCancelled = true
                    }
                }
                return values.current()
            }
        }
        val coordinator = MaintenanceCoordinator(generations)
        val runner = ReminderBroadcastRunner(coordinator, StandardTestDispatcher(testScheduler))
        var finishes = 0
        var calibrations = 0
        var deliveries = 0
        val job = runner.launch({ finishes++ }, { calibrations++ }, DataGeneration(37)) { deliveries++ }
        runCurrent() // Start source validation on IO before beginning maintenance.
        val ready = CompletableDeferred<Unit>()
        val maintenance = backgroundScope.async { coordinator.withSession { ready.complete(Unit) } }
        try {
            assertTrue(readEntered.isCompleted, "Source validation must hold counted admission on IO")
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            assertFalse(ready.isCompleted)
            advanceTimeBy(7_999)
            runCurrent()
            assertFalse(job.isCompleted)
            assertEquals(0, finishes)
            assertFalse(readCancelled)

            // The next metadata read may succeed, but this arrival's already suspended read never returns.
            blockReads = false
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, finishes, "Stalled metadata capture must finish the broadcast once at 8 seconds")
            assertTrue(job.isCancelled, "Capture timeout must propagate cancellation")
            assertTrue(readCancelled, "Timeout must cancel the metadata read")
            assertEquals(0, deliveries)
            assertEquals(0, calibrations, "Timeout must not enqueue a rejected payload or calibration")
            assertTrue(ready.isCompleted, "Capture admission must release so maintenance can become READY")
            maintenance.await()
            assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        } finally {
            // Keep intended RED a behavior assertion, with no detached read/session left behind.
            blockReads = false
            job.cancelAndJoin()
            maintenance.cancelAndJoin()
        }
    }

    @Test fun readyRejectionFinishesImmediatelyAndRequestsOnlyFreshCalibration(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var deliveries = 0
        var calibrations = 0
        var finishes = 0
        fixture.coordinator.withSession {
            val job = runner.launch({ finishes++ }, { calibrations++ }, DataGeneration(37)) { deliveries++ }
            assertTrue(job.isCompleted, "Busy broadcast must end before IO dispatch or unfreeze")
            assertEquals(1, finishes)
            assertEquals(1, calibrations)
            assertEquals(0, deliveries)
        }
        runCurrent()
        assertEquals(0, deliveries)
        assertEquals(1, finishes)
        assertEquals(1, calibrations)
    }

    @Test fun busyEnqueueFailureStillFinishesWithoutDelivering(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var deliveries = 0
        var finishes = 0
        fixture.coordinator.withSession {
            val job = runner.launch({ finishes++ }, { error("synthetic enqueue failure") }, DataGeneration(37)) { deliveries++ }
            assertTrue(job.isCompleted)
            assertEquals(1, finishes)
            assertEquals(0, deliveries)
        }
        runCurrent()
        assertEquals(0, deliveries)
    }

    @Test fun maintenanceBetweenArrivalAndIORejectsWithoutQueuingTheOperation(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var deliveries = 0
        var calibrations = 0
        var finishes = 0
        val job = runner.launch({ finishes++ }, { calibrations++ }, DataGeneration(37)) { deliveries++ }
        assertFalse(job.isCompleted)
        fixture.coordinator.withSession {
            runCurrent()
            assertEquals(0, deliveries)
            assertEquals(1, calibrations)
            assertEquals(1, finishes)
            assertTrue(job.isCompleted)
        }
        runCurrent()
        assertEquals(0, deliveries)
        assertEquals(1, calibrations)
    }

    @Test fun committedGenerationRejectsTheArrivalTokenWithoutRequestingCalibration(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var deliveries = 0
        var calibrations = 0
        var finishes = 0
        val job = runner.launch({ finishes++ }, { calibrations++ }, DataGeneration(37)) { deliveries++ }
        fixture.coordinator.withSession { session ->
            fixture.coordinator.withMaintenance(session) { fixture.generations.commit(session.generation) {} }
        }
        runCurrent()
        assertEquals(0, deliveries)
        assertEquals(0, calibrations)
        assertEquals(1, finishes)
        assertTrue(job.isCompleted)
    }

    @Test fun timeoutCancelsWorkFinishesOnceAndAllowsMaintenanceToDrain(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var cancelledWork = false
        var finishes = 0
        val job = runner.launch({ finishes++ }, { error("must not calibrate on timeout") }, DataGeneration(37)) {
            try { CompletableDeferred<Unit>().await() } finally { cancelledWork = true }
        }
        runCurrent()
        val maintenance = backgroundScope.async { fixture.coordinator.withSession {} }
        runCurrent()
        assertEquals(MaintenanceState.DRAINING, fixture.coordinator.state.value)
        advanceTimeBy(7_999)
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(0, finishes)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(job.isCancelled, "Timeout cancellation must propagate after finishing")
        assertTrue(cancelledWork)
        assertEquals(1, finishes)
        maintenance.await()
        assertEquals(MaintenanceState.IDLE, fixture.coordinator.state.value)
    }

    @Test fun externalCancellationFinishesOnceAndReleasesAdmission(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var finishes = 0
        var calibrations = 0
        val job = runner.launch({ finishes++ }, { calibrations++ }, DataGeneration(37)) { CompletableDeferred<Unit>().await() }
        runCurrent()
        job.cancel(CancellationException("synthetic broadcast cancellation"))
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, finishes)
        assertEquals(0, calibrations)
        fixture.coordinator.withSession { assertEquals(MaintenanceState.READY, fixture.coordinator.state.value) }
    }

    @Test fun repositoryFailureFinishesAndDoesNotLeakAdmissionOrReplay(): Unit = runTest {
        val fixture = ReminderAdmissionFixture()
        val runner = ReminderBroadcastRunner(fixture.coordinator, StandardTestDispatcher(testScheduler))
        var attempts = 0
        var calibrations = 0
        var finishes = 0
        val job = runner.launch({ finishes++ }, { calibrations++ }, DataGeneration(37)) { attempts++; error("synthetic read failure") }
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, finishes)
        assertEquals(1, attempts)
        assertEquals(0, calibrations)
        fixture.coordinator.withSession {}
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun entryPointProviderFailureFinishesWithoutFallbackOrDelivery(): Unit = runTest {
        var deliveries = 0
        var calibrations = 0
        var finishes = 0
        startReminderBroadcast(
            RuntimeEnvironment.getApplication(), { error("synthetic provider failure") },
            { finishes++ }, { calibrations++ }, DataGeneration(37), { deliveries++ },
        )
        runCurrent()
        assertEquals(1, finishes)
        assertEquals(0, calibrations)
        assertEquals(0, deliveries)
    }
}
