package com.example.lifemanager.notification

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.domain.maintenance.MaintenanceState
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Run on the owned read-only AVD. No business writes or database resets. */
@RunWith(AndroidJUnit4::class)
class WorkerMaintenanceDeviceTest {
    @Test fun defaultWorkerFactoryUsesTheApplicationCoordinatorAlsoUsedByReceivers(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = EntryPointAccessors.fromApplication(context, TodoReminderEntryPoint::class.java)
            .maintenanceCoordinator()
        // WorkManager's reflection fallback requires a Java (Context, WorkerParameters) constructor.
        val factory = factory()
        val worker = assertIs<ReminderReconciliationWorker>(factory.createWorkerWithDefaultFallback(
            context, ReminderReconciliationWorker::class.java.name, parameters(factory),
        ))
        coordinator.withSession {
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        }
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun drainingTheApplicationCoordinatorRejectsTheActualBackgroundWorker(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = EntryPointAccessors.fromApplication(context, TodoReminderEntryPoint::class.java)
            .maintenanceCoordinator()
        val generation = coordinator.capture()
        val release = CompletableDeferred<Unit>()
        val existing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.run(generation) { release.await() } }
        val maintenance = async(start = CoroutineStart.UNDISPATCHED) { coordinator.withSession {} }
        try {
            assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
            assertEquals(ListenableWorker.Result.retry(), ReminderReconciliationWorker(context, parameters(factory())).doWork())
        } finally {
            release.complete(Unit)
            existing.await()
            maintenance.await()
        }
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    private fun factory() = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
    }

    private fun parameters(factory: WorkerFactory): WorkerParameters {
        val executor = Executor { it.run() }
        return WorkerParameters(
            UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory,
            object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data) =
                    CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused progress" }
            },
            object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo) =
                    CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused foreground" }
            },
        )
    }
}
