package com.example.lifemanager.ui.common

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationAccessTest {
    @Test fun rejectedReadRetriesFreshWithoutNeedingAnotherMaintenanceEmissionButWritesNeverRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestGenerations(3)
            val access = GenerationAccess(store, MaintenanceCoordinator(store))
            // The brief freeze is already back at IDLE when the rejected read reaches its catch.
            store.nextCurrentFailures.addLast(MaintenanceBusyException())
            var reads = 0
            var published = ""
            access.read({ reads++; "fresh" }) { _, value -> published = value }
            assertEquals(1, reads)
            assertEquals("fresh", published)
            store.nextCurrentFailures.addLast(MaintenanceBusyException())
            var writes = 0
            assertFailsWith<MaintenanceBusyException> { access.run(DataGeneration(3)) { writes++ } }
            assertEquals(0, writes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun missingGenerationFailsClosedAndBusyEventsAreNotQueued() = runTest {
        val store = TestGenerations(12)
        val coordinator = MaintenanceCoordinator(store)
        val access = GenerationAccess(store, coordinator)
        assertFailsWith<IllegalStateException> { access.eventToken(null) }
        assertEquals(DataGeneration(12), access.eventToken(store.current()))
        coordinator.withSession {
            assertFailsWith<MaintenanceBusyException> { access.eventToken(store.current()) }
        }
    }

    @Test fun freshReadAndPublicationDrainBeforeMaintenanceIsReady() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestGenerations(9)
            val coordinator = MaintenanceCoordinator(store)
            val access = GenerationAccess(store, coordinator)
            val loadStarted = CompletableDeferred<Unit>()
            val finishLoad = CompletableDeferred<Unit>()
            var published: Pair<DataGeneration, String>? = null
            val reader = async {
                access.read({ loadStarted.complete(Unit); finishLoad.await(); "old data" }) { token, value ->
                    published = token to value
                }
            }
            loadStarted.await()
            val maintenance = async { coordinator.withSession { assertEquals(DataGeneration(9) to "old data", published) } }
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            finishLoad.complete(Unit)
            reader.await()
            maintenance.await()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun deferredPresentationSurvivesCancelButIsDiscardedAfterGenerationChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestGenerations(4)
            val coordinator = MaintenanceCoordinator(store)
            val access = GenerationAccess(store, coordinator)
            var publications = 0
            for (replace in listOf(false, true)) {
                val finish = CompletableDeferred<Unit>()
                val owner = launch {
                    coordinator.withSession { session ->
                        finish.await()
                        if (replace) coordinator.withMaintenance(session) { store.commit(session.generation) {} }
                    }
                }
                runCurrent()
                val pending = async { access.publishResult(DataGeneration(4)) { publications++ } }
                runCurrent()
                assertFalse(pending.isCompleted)
                finish.complete(Unit)
                owner.join()
                assertEquals(!replace, pending.await())
            }
            assertEquals(1, publications)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun staleOperationNeverReachesMutationEvenWhenIdsAreReused() = runTest {
        val store = TestGenerations(4)
        val coordinator = MaintenanceCoordinator(store)
        val access = GenerationAccess(store, coordinator)
        coordinator.withSession { session -> coordinator.withMaintenance(session) { store.commit(session.generation) {} } }
        var mutated = false
        assertFailsWith<com.example.lifemanager.domain.maintenance.StaleGenerationException> {
            access.run(DataGeneration(4)) { mutated = true }
        }
        assertFalse(mutated)
        assertTrue(access.maintenance.value == MaintenanceState.IDLE)
    }
}
