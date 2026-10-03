package com.example.lifemanager.ui.common

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Shared admission for versioned UI data, writes and result publication. */
@Singleton
class GenerationAccess @Inject constructor(
    private val generationsRepository: DataGenerationRepository,
    private val coordinator: MaintenanceCoordinator,
) {
    val generations get() = generationsRepository.observe()
    val maintenance get() = coordinator.state

    // This is an early rejection, not a permit. run() checks again after coroutine dispatch.
    fun eventToken(published: DataGeneration?): DataGeneration {
        if (maintenance.value != MaintenanceState.IDLE) throw MaintenanceBusyException()
        return checkNotNull(published) { "数据尚未读取，请稍后重试" }
    }

    suspend fun <T> run(token: DataGeneration, operation: suspend () -> T): T =
        coordinator.run(token, operation)

    suspend fun <T> read(load: suspend () -> T, publish: suspend (DataGeneration, T) -> Unit) {
        while (true) {
            try {
                val token = coordinator.capture()
                coordinator.run(token) {
                    val data = load()
                    // Maintenance drains both the query and the actual UI publication.
                    withContext(Dispatchers.Main.immediate) { publish(token, data) }
                }
                return
            } catch (_: MaintenanceBusyException) {
                // A short freeze may already be IDLE, with no new StateFlow emission to restart a listener.
                maintenance.first { it == MaintenanceState.IDLE }
                yield()
            } catch (_: StaleGenerationException) {
                // Only fresh, side-effect-free reads retry. Ordinary run() mutations never do.
                yield()
            }
        }
    }

    suspend fun publishResult(token: DataGeneration, publish: () -> Unit): Boolean {
        while (true) {
            try {
                coordinator.run(token) { withContext(Dispatchers.Main.immediate) { publish() } }
                return true
            } catch (_: MaintenanceBusyException) {
                // Only a UI result waits here. Never queue a rejected database mutation.
                maintenance.first { it == MaintenanceState.IDLE }
            } catch (_: StaleGenerationException) {
                return false
            }
        }
    }
}
