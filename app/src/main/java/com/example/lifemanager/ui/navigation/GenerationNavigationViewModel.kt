package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.ui.common.GenerationAccess
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

interface GenerationNavigationRequest {
    val token: Long
    val entityId: Long
    val originalDataGeneration: DataGeneration
}

/** Shared admission for the three Activity notification owners; never waits for UI under a permit. */
abstract class GenerationNavigationViewModel<R : GenerationNavigationRequest>(
    private val access: GenerationAccess,
    private val dispatcher: CoroutineDispatcher,
    private val requestFor: (Long, Long, DataGeneration) -> R,
) : ViewModel() {
    private val sequence = AtomicLong()
    private val requests = MutableStateFlow<R?>(null)
    val pending = requests.asStateFlow()

    init {
        viewModelScope.launch(dispatcher) {
            try {
                access.generations.collectLatest { observed ->
                    withContext(Dispatchers.Main.immediate) {
                        // A delayed older observation must not erase an already captured newer arrival.
                        requests.update { request ->
                            if (request?.let { it.originalDataGeneration.value < observed.value } == true) null
                            else request
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Unreadable metadata is not a generation change. Admission still fails closed.
            }
        }
    }

    fun open(entityId: Long) {
        openRequest(entityId, null, captureLocal = true)
    }

    /** Notification payloads must supply their original generation; missing is never recaptured. */
    fun openFromNotification(entityId: Long, sourceGeneration: DataGeneration?) {
        if (sourceGeneration == null) return
        openRequest(entityId, sourceGeneration, captureLocal = false)
    }

    private fun openRequest(entityId: Long, sourceGeneration: DataGeneration?, captureLocal: Boolean) {
        if (entityId <= 0L || access.maintenance.value != MaintenanceState.IDLE) return
        val token = sequence.incrementAndGet()
        viewModelScope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                // Enter the counted strict read before dispatch; never substitute a later generation.
                val original = if (captureLocal) access.capture() else requireNotNull(sourceGeneration)
                val request = requestFor(token, entityId, original)
                access.publishResult(original) {
                    if (sequence.get() == token) requests.value = request
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Busy/stale/unreadable arrivals are rejected, without replacing the prior request.
            }
        }
    }

    fun consume(token: Long) {
        requests.update { if (it?.token == token) null else it }
    }

    /** Graph readiness may suspend. Only the actual navigation effect holds the original permit. */
    suspend fun deliver(
        request: R,
        awaitGraph: suspend () -> Unit = {},
        navigate: () -> Unit,
    ): Boolean = deliverIfAllowed(request, awaitGraph, canNavigate = { true }, navigate = navigate)

    /** A UI veto retains the request; optional readiness waiting/retry occurs outside the permit. */
    suspend fun deliverIfAllowed(
        request: R,
        awaitGraph: suspend () -> Unit = {},
        awaitUiReady: (suspend () -> Unit)? = null,
        canNavigate: () -> Boolean,
        navigate: () -> Unit,
    ): Boolean {
        val original = request.originalDataGeneration
        awaitGraph()
        return withContext(dispatcher) {
            while (true) {
                try {
                    var uiBlocked = false
                    val delivered = access.run(original) {
                        withContext(Dispatchers.Main.immediate) {
                            if (requests.value != request) false
                            else if (!canNavigate()) { uiBlocked = true; false }
                            else { navigate(); true }
                        }
                    }
                    if (!uiBlocked || awaitUiReady == null) return@withContext delivered
                    // Only a UI veto is retryable here. Stale/superseded/unreadable requests stop.
                    awaitUiReady()
                    yield()
                } catch (_: MaintenanceBusyException) {
                    // This is an already admitted arrival, not a new event queued during Busy.
                    access.maintenance.first { it == MaintenanceState.IDLE }
                    yield()
                } catch (_: StaleGenerationException) {
                    return@withContext false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    return@withContext false
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }
}
