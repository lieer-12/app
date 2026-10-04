package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.PausingMainDispatcher
import com.example.lifemanager.ui.common.TestGenerations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same maintenance contract applies to all three Activity notification owners. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class NavigationMaintenanceTest(private val module: Module) {
    @Test fun replacementInvalidatesAnUnconsumedNotification() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        assertEquals(42L, assertNotNull(navigation.pending()).entityId)

        replace()
        advanceUntilIdle()

        assertEquals(DataGeneration(13), generations.current())
        assertNull(navigation.pending(), "The old notification must not target a reused ID after replacement")
    } }

    @Test fun replacementInvalidatesPendingEvenBeforeMaintenanceUnfreezes() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()

        coordinator.withSession { session ->
            coordinator.withMaintenance(session) { generations.commit(session.generation) {} }
            runCurrent()

            assertEquals(MaintenanceState.READY, coordinator.state.value)
            assertEquals(DataGeneration(13), generations.current())
            assertNull(navigation.pending(), "A true generation change invalidates pending navigation while frozen")
        }
    } }

    @Test fun arrivalBeforeReplacementCannotBeRecapturedOnItsLaterDispatcherTurn() = runTest { scenario {
        navigation.open(42)
        // No dispatcher turn between arrival and replacement: capture belongs to arrival.
        replace()
        advanceUntilIdle()

        assertNull(navigation.pending(), "Dispatching an old arrival must not tag it with the new generation")
    } }

    @Test fun readyFreezeRejectsIncomingAndPreservesThePriorPendingRequest() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())

        coordinator.withSession {
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            navigation.open(43)
            runCurrent()
            assertEquals(prior, navigation.pending(), "A busy arrival must not replace the admitted request")
        }
        advanceUntilIdle()
        assertEquals(prior, navigation.pending(), "A rejected arrival must not replay after unfreezing")

        navigation.open(44)
        advanceUntilIdle()
        assertEquals(44L, assertNotNull(navigation.pending()).entityId)
    } }

    @Test fun drainingFreezeRejectsIncomingWithoutQueuingIt() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writer = launch {
            coordinator.run(DataGeneration(12)) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val owner = launch { coordinator.withSession { finish.await() } }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            navigation.open(43)
            runCurrent()
            assertEquals(prior, navigation.pending(), "New arrivals must be rejected while draining admitted work")
        } finally {
            release.complete(Unit)
            writer.join()
            finish.complete(Unit)
            owner.join()
        }
        advanceUntilIdle()
        assertEquals(prior, navigation.pending())
    } }

    @Test fun runningFreezeRejectsIncomingWithoutReplacingThePriorRequest() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())

        coordinator.withSession { session ->
            coordinator.withMaintenance(session) {
                assertEquals(MaintenanceState.RUNNING, coordinator.state.value)
                navigation.open(43)
                runCurrent()
                assertEquals(prior, navigation.pending(), "Maintenance work must not admit a new arrival")
            }
        }
        advanceUntilIdle()
        assertEquals(prior, navigation.pending())
    } }

    @Test fun shortFreezeRejectsArrivalEvenWhenIdleReturnsBeforeDispatch() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())

        coordinator.withSession { navigation.open(43) }
        // READY -> IDLE can be conflated before the navigation coroutine observes it.
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        advanceUntilIdle()

        assertEquals(prior, navigation.pending(), "Busy must be checked at arrival, not after dispatch")
    } }

    @Test fun cancellingMaintenancePreservesThePriorPendingRequest() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = launch {
            coordinator.withSession { ready.complete(Unit); finish.await() }
        }
        try {
            ready.await()
            runCurrent()
            assertEquals(prior, navigation.pending(), "Freezing alone must not invalidate pending navigation")
        } finally {
            owner.cancelAndJoin()
        }
        advanceUntilIdle()

        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
        assertEquals(DataGeneration(12), generations.current())
        assertEquals(prior, navigation.pending())
        navigation.consume(prior.token)
        assertNull(navigation.pending())
    } }

    @Test fun lateAcknowledgementCannotConsumeANewerRequestAfterReplacement() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val old = assertNotNull(navigation.pending())
        replace()
        advanceUntilIdle()

        navigation.open(42)
        advanceUntilIdle()
        val newer = assertNotNull(navigation.pending())
        assertEquals(42L, newer.entityId)
        assertNotEquals(old.token, newer.token)
        navigation.consume(old.token)
        advanceUntilIdle()

        assertEquals(newer, navigation.pending(), "The old Compose acknowledgement must not clear the newer request")
        navigation.consume(newer.token)
        assertNull(navigation.pending())
    } }

    @Test fun delayedGenerationObservationCannotClearANewerArrival() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val old = assertNotNull(navigation.pending())

        replace()
        // The observer has not had a dispatcher turn to invalidate the old request yet.
        navigation.open(43)
        advanceUntilIdle()

        val newer = assertNotNull(navigation.pending())
        assertEquals(43L, newer.entityId)
        assertNotEquals(old.token, newer.token)
        navigation.consume(old.token)
        assertEquals(newer, navigation.pending())
    } }

    @Test fun latestArrivalWinsAndAnOlderAcknowledgementCannotClearIt() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val first = assertNotNull(navigation.pending())

        navigation.open(43)
        navigation.open(44)
        advanceUntilIdle()
        val newest = assertNotNull(navigation.pending())
        assertEquals(44L, newest.entityId)
        assertNotEquals(first.token, newest.token)
        navigation.consume(first.token)
        assertEquals(newest, navigation.pending())
    } }

    @Test fun unreadableMetadataRejectsIncomingAndKeepsThePriorRequest() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())
        generations.currentFailure = IllegalStateException("Generation metadata is unreadable")

        navigation.open(43)
        advanceUntilIdle()
        assertEquals(prior, navigation.pending(), "Metadata failure must not replace pending navigation or fall back to zero")

        generations.currentFailure = null
        advanceUntilIdle()
        assertEquals(prior, navigation.pending(), "Metadata recovery must not replay the rejected arrival")
        navigation.open(44)
        advanceUntilIdle()
        assertEquals(44L, assertNotNull(navigation.pending()).entityId)
    } }

    @Test fun metadataFailureAtArrivalCannotBeHiddenByRecoveryBeforeDispatch() = runTest { scenario {
        generations.currentFailure = IllegalStateException("Generation metadata is missing")
        navigation.open(42)
        // No dispatcher turn: a delayed capture would incorrectly succeed after recovery.
        generations.currentFailure = null
        advanceUntilIdle()

        assertNull(navigation.pending(), "Strict arrival capture must reject unreadable metadata without a zero fallback")
        navigation.open(43)
        advanceUntilIdle()
        assertEquals(43L, assertNotNull(navigation.pending()).entityId)
    } }

    @Test fun invalidIdsDoNotDisplaceAPendingRequest() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val prior = assertNotNull(navigation.pending())

        navigation.open(0)
        navigation.open(-1)
        advanceUntilIdle()

        assertEquals(prior, navigation.pending())
    } }

    @Test fun graphWaitDoesNotHoldAPermitAndReplacementRejectsTheOriginalTarget() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val request = assertNotNull(navigation.pending())
        assertEquals(DataGeneration(12), request.originalDataGeneration)
        val graphEntered = CompletableDeferred<Unit>()
        val graphReady = CompletableDeferred<Unit>()
        val finishMaintenance = CompletableDeferred<Unit>()
        val destinations = mutableListOf<Long>()
        val delivery = async {
            navigation.deliver(request, { graphEntered.complete(Unit); graphReady.await() }) {
                destinations += request.entityId
            }
        }
        graphEntered.await()
        val owner = launch {
            coordinator.withSession { session ->
                coordinator.withMaintenance(session) { generations.commit(session.generation) {} }
                finishMaintenance.await()
            }
        }
        try {
            runCurrent()
            assertEquals(MaintenanceState.READY, coordinator.state.value,
                "An uninitialized graph must not hold a navigation permit across its wait")
            assertEquals(DataGeneration(13), generations.current())
            assertNull(navigation.pending())
            finishMaintenance.complete(Unit)
            owner.join()
            graphReady.complete(Unit)
            assertFalse(delivery.await())
            assertTrue(destinations.isEmpty(), "A delayed Compose effect must not navigate to a reused ID")

            navigation.open(42)
            advanceUntilIdle()
            val current = assertNotNull(navigation.pending())
            assertEquals(DataGeneration(13), current.originalDataGeneration)
            assertTrue(navigation.deliver(current, {}) { destinations += current.entityId })
            assertEquals(listOf(42L), destinations)
        } finally {
            graphReady.complete(Unit)
            finishMaintenance.complete(Unit)
            delivery.cancelAndJoin()
            owner.cancelAndJoin()
        }
    } }

    @Test fun admittedNavigationHoldsItsPermitThroughTheActualMainEffect() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val request = assertNotNull(navigation.pending())
        val destinations = mutableListOf<Long>()
        main.pauseNext = true
        val delivery = async { navigation.deliver(request, {}) { destinations += request.entityId } }
        runCurrent()
        assertTrue(main.hasHeldPublication)
        assertTrue(destinations.isEmpty())
        val finishMaintenance = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession { finishMaintenance.await() } }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value,
                "Maintenance must drain the Main navigation effect, not just its background validation")
            assertFalse(delivery.isCompleted)
            main.resumeHeld()
            runCurrent()
            assertTrue(delivery.await())
            assertEquals(listOf(42L), destinations)
            assertEquals(MaintenanceState.READY, coordinator.state.value)
            assertEquals(request, navigation.pending(), "Navigation acknowledgement belongs to module handoff")
        } finally {
            main.resumeHeld()
            finishMaintenance.complete(Unit)
            delivery.cancelAndJoin()
            owner.cancelAndJoin()
        }
    } }

    @Test fun newerArrivalPreventsAnOlderMainEffectFromNavigating() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val old = assertNotNull(navigation.pending())
        val destinations = mutableListOf<Long>()
        main.pauseNext = true
        val delivery = async { navigation.deliver(old, {}) { destinations += old.entityId } }
        try {
            runCurrent()
            assertTrue(main.hasHeldPublication)
            navigation.open(43)
            advanceUntilIdle()
            val newer = assertNotNull(navigation.pending())
            assertEquals(43L, newer.entityId)
            assertEquals(DataGeneration(12), newer.originalDataGeneration)

            main.resumeHeld()
            assertFalse(delivery.await())
            assertTrue(destinations.isEmpty(), "The original request must still own pending state at Main publication")
            navigation.consume(old.token)
            assertEquals(newer, navigation.pending())
            assertTrue(navigation.deliver(newer, {}) { destinations += newer.entityId })
            assertEquals(listOf(43L), destinations)
        } finally {
            main.resumeHeld()
            delivery.cancelAndJoin()
        }
    } }

    @Test fun cancellingGraphWaitPreservesPendingForANewEffect() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val request = assertNotNull(navigation.pending())
        val graphEntered = CompletableDeferred<Unit>()
        val graphReady = CompletableDeferred<Unit>()
        val destinations = mutableListOf<Long>()
        val delivery = async {
            navigation.deliver(request, { graphEntered.complete(Unit); graphReady.await() }) {
                destinations += request.entityId
            }
        }
        graphEntered.await()
        delivery.cancelAndJoin()
        assertTrue(destinations.isEmpty())
        assertEquals(request, navigation.pending())
        coordinator.withSession {}
        assertEquals(DataGeneration(12), generations.current())

        assertTrue(navigation.deliver(request, {}) { destinations += request.entityId })
        assertEquals(listOf(42L), destinations)
        assertEquals(request, navigation.pending())
        navigation.consume(request.token)
        assertNull(navigation.pending())
    } }

    @Test fun existingDeliverySurvivesCancelledFreezeWithItsOriginalGeneration() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val request = assertNotNull(navigation.pending())
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = launch { coordinator.withSession { ready.complete(Unit); finish.await() } }
        ready.await()
        val destinations = mutableListOf<Long>()
        val delivery = async { navigation.deliver(request, {}) { destinations += request.entityId } }
        try {
            runCurrent()
            assertFalse(delivery.isCompleted)
            assertEquals(request, navigation.pending())
            assertTrue(destinations.isEmpty())
            owner.cancelAndJoin()
            assertTrue(delivery.await())
            assertEquals(listOf(42L), destinations)
            assertEquals(DataGeneration(12), request.originalDataGeneration)
            assertEquals(request, navigation.pending())
        } finally {
            delivery.cancelAndJoin()
            owner.cancelAndJoin()
        }
    } }

    @Test fun deliveryRejectsUnreadableMetadataWithoutNavigatingOrConsuming() = runTest { scenario {
        navigation.open(42)
        advanceUntilIdle()
        val request = assertNotNull(navigation.pending())
        val destinations = mutableListOf<Long>()
        generations.currentFailure = IllegalStateException("Generation metadata is unreadable")

        assertFalse(navigation.deliver(request, {}) { destinations += request.entityId })
        assertTrue(destinations.isEmpty())
        assertEquals(request, navigation.pending())
        generations.currentFailure = null
        assertTrue(navigation.deliver(request, {}) { destinations += request.entityId })
        assertEquals(listOf(42L), destinations)
        assertEquals(DataGeneration(12), request.originalDataGeneration)
    } }

    private suspend fun TestScope.scenario(block: suspend Fixture.() -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val main = PausingMainDispatcher(dispatcher)
        Dispatchers.setMain(main)
        val fixture = Fixture(module, dispatcher, main)
        try {
            advanceUntilIdle()
            fixture.block()
        } finally {
            main.resumeHeld()
            fixture.navigation.viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(module: Module, dispatcher: CoroutineDispatcher, val main: PausingMainDispatcher) {
        val generations = TestGenerations(12)
        val coordinator = MaintenanceCoordinator(generations)
        val access = GenerationAccess(generations, coordinator)
        val navigation = module.create(access, dispatcher)

        suspend fun replace() {
            coordinator.withSession { session ->
                coordinator.withMaintenance(session) { generations.commit(session.generation) {} }
            }
        }
    }

    enum class Module {
        TODO, SCHEDULE, SUBSCRIPTION;

        // Main verified behavioral RED against the old constructors before required DI was added.
        internal fun create(access: GenerationAccess, dispatcher: CoroutineDispatcher): NavigationModel = when (this) {
            TODO -> TodoNavigationViewModel(access, dispatcher).let { model ->
                NavigationModel(model, model::open, model::consume,
                    { request, awaitGraph, navigate -> model.deliver(request as TodoNavigationRequest, awaitGraph, navigate) },
                    { model.pending.value })
            }
            SCHEDULE -> ScheduleNavigationViewModel(access, dispatcher).let { model ->
                NavigationModel(model, model::open, model::consume,
                    { request, awaitGraph, navigate -> model.deliver(request as ScheduleNavigationRequest, awaitGraph, navigate) },
                    { model.pending.value })
            }
            SUBSCRIPTION -> SubscriptionNavigationViewModel(access, dispatcher).let { model ->
                NavigationModel(model, model::open, model::consume,
                    { request, awaitGraph, navigate -> model.deliver(request as SubscriptionNavigationRequest, awaitGraph, navigate) },
                    { model.pending.value })
            }
        }
    }

    // An adapter over real production state, not a fake navigation implementation.
    internal class NavigationModel(
        val viewModel: ViewModel,
        val open: (Long) -> Unit,
        val consume: (Long) -> Unit,
        val deliver: suspend (GenerationNavigationRequest, suspend () -> Unit, () -> Unit) -> Boolean,
        val pending: () -> GenerationNavigationRequest?,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modules(): List<Array<Any>> = Module.entries.map { arrayOf<Any>(it) }
    }
}
