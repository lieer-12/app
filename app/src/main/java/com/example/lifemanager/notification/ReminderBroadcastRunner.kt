package com.example.lifemanager.notification

import android.content.Context
import android.util.Log
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

@Singleton
class ReminderBroadcastRunner @Inject constructor(
    private val coordinator: MaintenanceCoordinator,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    fun launch(
        finish: () -> Unit,
        onBusy: () -> Unit,
        operation: suspend () -> Unit,
    ): Job = CoroutineScope(dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            withTimeout(8_000) {
                // Count capture at arrival; its metadata read also belongs to the deadline.
                val originalToken = coordinator.capture()
                // Even an immediate capture must dispatch validation onto IO, retaining its token.
                yield()
                coordinator.run(originalToken) { operation() }
            }
        } catch (_: MaintenanceBusyException) {
            // Only fresh reconciliation is requested; no old payload is retained or replayed.
            try {
                onBusy()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Log.w(TAG, "Unable to request reminder reconciliation")
            }
        } catch (_: StaleGenerationException) {
            Log.d(TAG, "Discarded stale reminder broadcast")
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "Reminder broadcast timed out")
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Exceptions may contain repository/payload contents; log only the failure category.
            Log.w(TAG, "Unable to validate or deliver reminder broadcast")
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "ReminderBroadcast"

        fun fromApplication(context: Context, dispatcher: CoroutineDispatcher): ReminderBroadcastRunner {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext, TodoReminderEntryPoint::class.java,
            )
            return ReminderBroadcastRunner(entryPoint.maintenanceCoordinator(), dispatcher)
        }
    }
}

/** Provider failures must also finish a pending broadcast, before coroutine ownership is acquired. */
internal fun startReminderBroadcast(
    context: Context,
    runnerProvider: (Context) -> ReminderBroadcastRunner,
    finish: () -> Unit,
    onBusy: (Context) -> Unit,
    operation: suspend () -> Unit,
) {
    val runner = try {
        runnerProvider(context.applicationContext)
    } catch (error: CancellationException) {
        finish()
        throw error
    } catch (_: Exception) {
        Log.w("ReminderBroadcast", "Unable to obtain reminder admission service")
        finish()
        return
    }
    runner.launch(finish, { onBusy(context.applicationContext) }, operation)
}
