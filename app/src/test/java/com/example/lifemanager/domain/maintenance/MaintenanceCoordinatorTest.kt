package com.example.lifemanager.domain.maintenance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceCoordinatorTest {
    @Test fun capturesPersistedGenerationAndDoesNotAdvanceOrdinaryWrites() = runTest {
        val store = MemoryGenerations(73)
        val coordinator = MaintenanceCoordinator(store)
        val token = coordinator.capture()
        assertEquals(DataGeneration(73), token)
        assertEquals("saved", coordinator.run(token) { "saved" })
        assertEquals(token, store.current())
    }

    @Test fun drainingRejectsNewWritesWithoutQueuingThemAfterFinish() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val token = coordinator.capture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writer = async { coordinator.run(token) { entered.complete(Unit); release.await() } }
        entered.await()
        val maintenance = async { coordinator.withSession { ready.complete(Unit); finish.await() } }
        runCurrent()
        assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
        assertFalse(ready.isCompleted)
        var newWrites = 0
        assertFailsWith<MaintenanceBusyException> { coordinator.run(token) { newWrites++ } }
        assertFailsWith<MaintenanceBusyException> { coordinator.capture() }
        assertFailsWith<MaintenanceBusyException> { coordinator.withSession {} }
        release.complete(Unit)
        writer.await()
        ready.await()
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        finish.complete(Unit)
        maintenance.await()
        assertEquals(0, newWrites)
        coordinator.run(token) { newWrites++ }
        assertEquals(1, newWrites)
    }

    @Test fun maintenanceWaitsForEveryAdmittedWriter() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val token = coordinator.capture()
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val first = async { coordinator.run(token) { firstRelease.await() } }
        val second = async { coordinator.run(token) { secondRelease.await() } }
        runCurrent()
        val maintenance = async { coordinator.withSession { ready.complete(Unit) } }
        runCurrent()
        firstRelease.complete(Unit)
        first.await()
        runCurrent()
        assertFalse(ready.isCompleted)
        secondRelease.complete(Unit)
        second.await()
        maintenance.await()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun cancelledDrainUnfreezesWithoutAdvancingOrLosingExistingWriter() = runTest {
        val store = MemoryGenerations(8)
        val coordinator = MaintenanceCoordinator(store)
        val token = coordinator.capture()
        val release = CompletableDeferred<Unit>()
        val writer = async { coordinator.run(token) { release.await() } }
        runCurrent()
        val maintenance = async { coordinator.withSession {} }
        runCurrent()
        maintenance.cancelAndJoin()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertEquals(token, coordinator.capture())
        val next = async { coordinator.withSession {} }
        runCurrent()
        assertFalse(next.isCompleted)
        release.complete(Unit)
        writer.await()
        next.await()
        assertEquals(DataGeneration(8), store.current())
    }

    @Test fun failedOrCancelledWriterAlwaysReleasesAdmission() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val token = coordinator.capture()
        assertFailsWith<IllegalArgumentException> { coordinator.run(token) { throw IllegalArgumentException("synthetic failure") } }
        val writer = async { coordinator.run(token) { CompletableDeferred<Unit>().await() } }
        runCurrent()
        writer.cancelAndJoin()
        coordinator.withSession {}
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun staleDraftCannotWriteToRestoredEntityWithReusedId() = runTest {
        val store = MemoryGenerations(2)
        val coordinator = MaintenanceCoordinator(store)
        val token = coordinator.capture()
        val rows = mutableMapOf(41L to "old entity")
        coordinator.withSession { session ->
            coordinator.withMaintenance(session) {
                store.commit(session.generation) { rows[41] = "restored entity" }
            }
        }
        assertFalse(coordinator.isCurrent(token))
        assertFailsWith<StaleGenerationException> { coordinator.run(token) { rows[41] = "old draft" } }
        assertEquals("restored entity", rows[41])
        assertTrue(coordinator.isCurrent(coordinator.capture()))
    }

    @Test fun cancellingSessionPreservesGenerationAndOldResultValidity() = runTest {
        val store = MemoryGenerations(9)
        val coordinator = MaintenanceCoordinator(store)
        val token = coordinator.capture()
        coordinator.withSession {}
        assertEquals(token, store.current())
        assertTrue(coordinator.isCurrent(token))
    }

    @Test fun noMutexIsHeldWhileAwaitingHumanConfirmation() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        coordinator.withSession { session ->
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            assertFailsWith<MaintenanceBusyException> { coordinator.run(session.generation) { error("must not run") } }
            assertFalse(coordinator.isCurrent(session.generation))
        }
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun foreignExpiredAndUnownedSessionsCannotOperate() = runTest {
        val first = MaintenanceCoordinator(MemoryGenerations())
        val second = MaintenanceCoordinator(MemoryGenerations())
        val unrelatedScope = backgroundScope
        first.withSession { own ->
            second.withSession { foreign ->
                assertFailsWith<IllegalArgumentException> { first.withMaintenance(foreign) {} }
                first.withMaintenance(own) {}
            }
            val outsider = unrelatedScope.async {
                assertFailsWith<IllegalArgumentException> { first.withMaintenance(own) {} }
            }
            outsider.await()
            assertEquals(MaintenanceState.READY, first.state.value)
        }
        val expired = first.withSession { it }
        first.withSession {
            assertFailsWith<IllegalArgumentException> { first.withMaintenance(expired) {} }
        }
        assertEquals(MaintenanceState.IDLE, first.state.value)
    }

    @Test fun nestedAdmissionOrMaintenanceFailsImmediatelyInsteadOfDeadlocking() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val token = coordinator.capture()
        coordinator.run(token) {
            assertFailsWith<IllegalStateException> { coordinator.run(token) {} }
            assertFailsWith<IllegalStateException> { coordinator.withSession {} }
        }
        coordinator.withSession { session ->
            coordinator.withMaintenance(session) {
                assertFailsWith<IllegalStateException> { coordinator.withMaintenance(session) {} }
                assertFailsWith<IllegalStateException> { coordinator.withSession {} }
            }
        }
    }

    @Test fun concurrentSessionOperationsAreRejectedAndChildCancellationKeepsFreeze() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        coordinator.withSession { session ->
            val operation = async { coordinator.withMaintenance(session) { CompletableDeferred<Unit>().await() } }
            runCurrent()
            assertEquals(MaintenanceState.RUNNING, coordinator.state.value)
            assertFailsWith<MaintenanceBusyException> { coordinator.withMaintenance(session) {} }
            operation.cancelAndJoin()
            assertEquals(MaintenanceState.READY, coordinator.state.value)
        }
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun failedGenerationReadsDoNotLeakAdmissionOrFreeze() = runTest {
        val store = MemoryGenerations()
        val coordinator = MaintenanceCoordinator(store)
        store.failReads = true
        assertFailsWith<IllegalStateException> { coordinator.capture() }
        assertFailsWith<IllegalStateException> { coordinator.withSession {} }
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        store.failReads = false
        coordinator.withSession {}
    }

    @Test fun cancellationDuringSessionOperationKeepsFreezeUntilScopeExit() = runTest {
        val store = MemoryGenerations(11)
        val coordinator = MaintenanceCoordinator(store)
        coordinator.withSession { session ->
            assertFailsWith<CancellationException> {
                coordinator.withMaintenance(session) { throw CancellationException("provider cancelled") }
            }
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            assertEquals(DataGeneration(11), store.current())
        }
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun cancellationAtGenerationReadReturnCannotLeaveUndeliveredSessionFrozen() = runTest {
        val store = MemoryGenerations()
        val coordinator = MaintenanceCoordinator(store)
        store.cancelRead = true
        var bodyEntered = false
        val owner = async { coordinator.withSession { bodyEntered = true } }
        runCurrent()
        assertTrue(owner.isCancelled)
        assertFalse(bodyEntered)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        store.cancelRead = false
        coordinator.withSession {}
    }

    @Test fun scopedCancellationAutomaticallyReleasesReadySession() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val ready = CompletableDeferred<Unit>()
        val owner = async { coordinator.withSession { ready.complete(Unit); CompletableDeferred<Unit>().await() } }
        ready.await()
        assertEquals(MaintenanceState.READY, coordinator.state.value)
        owner.cancelAndJoin()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertEquals(DataGeneration(0), coordinator.capture())
    }

    @Test fun scopedCancellationJoinsRunningChildBeforeReleasingSession() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val entered = CompletableDeferred<Unit>()
        val owner = async {
            coordinator.withSession { session ->
                launch { coordinator.withMaintenance(session) { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
                entered.await()
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        owner.cancelAndJoin()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun scopedFailedOperationReleasesSessionAndKeepsOriginalTokenValid() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations(4))
        val token = coordinator.capture()
        assertFailsWith<IllegalArgumentException> { coordinator.withSession { throw IllegalArgumentException("provider failed") } }
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertTrue(coordinator.isCurrent(token))
    }

    @Test fun inheritedMarkerWithReplacementJobIsRejectedBeforeEnteringMaintenance() = runTest {
        val coordinator = MaintenanceCoordinator(MemoryGenerations())
        val release = CompletableDeferred<Unit>()
        val replacement = Job()
        var detached: Job? = null
        var entered = false
        var rejected = false
        val failure = try {
            runCatching {
                coordinator.withSession { session ->
                    detached = launch(replacement, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            coordinator.withMaintenance(session) { entered = true; release.await() }
                        } catch (_: IllegalArgumentException) { rejected = true }
                    }
                }
            }.exceptionOrNull()
        } finally {
            release.complete(Unit)
            detached?.join()
            replacement.cancel()
        }
        assertNull(failure, "detached task must not make owner exit with a leaked freeze")
        assertFalse(entered)
        assertTrue(rejected)
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    private class MemoryGenerations(value: Long = 0) : DataGenerationRepository {
        private val values = MutableStateFlow(DataGeneration(value))
        var failReads = false
        var cancelRead = false
        override fun observe() = values
        override suspend fun current(): DataGeneration {
            check(!failReads) { "synthetic generation read failure" }
            if (cancelRead) currentCoroutineContext().cancel()
            return values.value
        }
        override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
            if (current() != expected) throw StaleGenerationException()
            mutation()
            return DataGeneration(expected.value + 1).also { values.value = it }
        }
    }
}
