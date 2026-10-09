package com.example.lifemanager.ui.navigation

import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.ui.common.GenerationAccess
import com.example.lifemanager.ui.common.TestGenerations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationSourceNavigationTest {
    // A live gate veto must retry even when Compose never renders the brief busy state.
    @Test fun lateUiLockWaitsOutsidePermitAndRetriesTheSameRequestWithoutAnotherDeliveryCall() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val generations = TestGenerations(37)
        val coordinator = MaintenanceCoordinator(generations)
        val owner = TodoNavigationViewModel(GenerationAccess(generations, coordinator), dispatcher)
        val graphEntered = CompletableDeferred<Unit>()
        val graphReady = CompletableDeferred<Unit>()
        val waitingForUi = CompletableDeferred<Unit>()
        val uiReady = MutableStateFlow(true)
        val destinations = mutableListOf<Long>()
        try {
            owner.openFromNotification(42, DataGeneration(37))
            runCurrent()
            val request = requireNotNull(owner.pending.value)
            val delivery = async {
                owner.deliverIfAllowed(request,
                    awaitGraph = { graphEntered.complete(Unit); graphReady.await() },
                    awaitUiReady = { waitingForUi.complete(Unit); uiReady.first { it }; Unit },
                    canNavigate = { uiReady.value }) { destinations += request.entityId }
            }
            runCurrent()
            assertTrue(graphEntered.isCompleted)
            uiReady.value = false
            graphReady.complete(Unit)
            runCurrent()
            assertTrue(waitingForUi.isCompleted)
            assertFalse(delivery.isCompleted)
            assertTrue(destinations.isEmpty())
            // A maintenance session can enter while delivery awaits UI: no data permit is held.
            coordinator.withSession { assertSame(request, owner.pending.value) }
            uiReady.value = true
            runCurrent()
            assertTrue(delivery.await())
            assertEquals(listOf(42L), destinations)
            assertEquals(DataGeneration(37), request.originalDataGeneration)
        } finally {
            owner.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    // Removing the live UI gate would discard backup consent/cancellation UI despite locked tabs.
    @Test fun busyUiKeepsAdmittedRequestWithoutNavigatingAndIdleUiDeliversItsOriginalSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val generations = TestGenerations(37)
        val access = GenerationAccess(generations, MaintenanceCoordinator(generations))
        val owners = listOf(TodoNavigationViewModel(access, dispatcher),
            ScheduleNavigationViewModel(access, dispatcher), SubscriptionNavigationViewModel(access, dispatcher))
        try {
            owners.forEach { it.openFromNotification(42, DataGeneration(37)) }
            runCurrent()
            owners.forEach { owner -> verifyUiAdmission(owner) }
        } finally {
            owners.forEach { it.viewModelScope.cancel() }
            Dispatchers.resetMain()
        }
    }

    private suspend fun <R : GenerationNavigationRequest> verifyUiAdmission(owner: GenerationNavigationViewModel<R>) {
        val request = requireNotNull(owner.pending.value)
        val destinations = mutableListOf<Long>()
        assertFalse(owner.deliverIfAllowed(request, canNavigate = { false }) { destinations += request.entityId })
        assertTrue(destinations.isEmpty())
        assertSame(request, owner.pending.value)
        assertTrue(owner.deliverIfAllowed(request, canNavigate = { true }) { destinations += request.entityId })
        assertEquals(listOf(42L), destinations)
        assertEquals(DataGeneration(37), request.originalDataGeneration)
    }

    @Test fun allOwnersRejectMissingAndOldClickSourcesAfterReusedIdRestore() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val generations = TestGenerations(37)
        val coordinator = MaintenanceCoordinator(generations)
        val access = GenerationAccess(generations, coordinator)
        val owners = listOf(TodoNavigationViewModel(access, dispatcher),
            ScheduleNavigationViewModel(access, dispatcher), SubscriptionNavigationViewModel(access, dispatcher))
        try {
            coordinator.withSession { session ->
                coordinator.withMaintenance(session) { generations.commit(session.generation) {} }
            }
            owners.forEach { it.openFromNotification(42, null) }
            runCurrent()
            owners.forEach { assertNull(it.pending.value) }
            owners.forEach { it.openFromNotification(42, DataGeneration(37)) }
            runCurrent()
            owners.forEach { assertNull(it.pending.value) }
            owners.forEach { it.openFromNotification(42, DataGeneration(38)) }
            runCurrent()
            owners.forEach { assertEquals(DataGeneration(38), it.pending.value?.originalDataGeneration) }
        } finally {
            owners.forEach { it.viewModelScope.cancel() }
            Dispatchers.resetMain()
        }
    }
}
