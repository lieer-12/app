package com.example.lifemanager.notification

import android.app.Application
import android.content.Context
import android.app.AlarmManager
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.data.repository.ScheduleRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import java.time.Instant
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.ui.common.TestGenerations
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.junit.Before
import org.junit.After

/** Worker admission, not a simulation of WorkManager's scheduling engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReminderWorkerMaintenanceTest {
    private lateinit var database: LifeManagerDatabase
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun cleanup() { database.close() }
    @Test fun workerRetriesInsteadOfRearmingRemindersDuringMaintenance(): Unit = runBlocking {
        val generations = TestGenerations(7)
        val coordinator = MaintenanceCoordinator(generations)
        TodoRepositoryImpl(database).saveTodo(Todo(title = "worker fixture", dueAt = Instant.now().plusSeconds(600)), emptyList())
        val worker = worker(coordinator)
        coordinator.withSession {
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            assertEquals(0, alarms().size)
        }
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, alarms().size, "A fresh retry must rebuild reminders from the current Room rows")
    }

    @Test fun unreadGenerationCannotBeTreatedAsSuccessfulCalibration(): Unit = runBlocking {
        val generations = TestGenerations(7)
        generations.currentFailure = IllegalStateException("synthetic unreadable metadata")
        val worker = worker(MaintenanceCoordinator(generations))
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(0, alarms().size)
    }

    @Test fun workerHonorsDisabledSettingsAndDoesNotRearmOnAnotherPass(): Unit = runBlocking {
        val coordinator = MaintenanceCoordinator(TestGenerations(7))
        val settings = ReminderTestSettings()
        TodoRepositoryImpl(database).saveTodo(Todo(title = "disabled fixture", dueAt = Instant.now().plusSeconds(3600)), emptyList())
        val worker = worker(coordinator, settings = settings)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, alarms().size)
        settings.updateSettings { it.copy(todoReminders = false, scheduleReminders = false, subscriptionReminders = false) }
        repeat(2) { assertEquals(ListenableWorker.Result.success(), worker.doWork()) }
        assertEquals(0, alarms().size)
    }

    @Test fun workerRetriesUnreadableSettingsWithoutInstallingAlarms(): Unit = runBlocking {
        val settings = ReminderTestSettings().apply { failure = IllegalStateException("settings unreadable") }
        val worker = worker(MaintenanceCoordinator(TestGenerations(7)), settings = settings)
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(0, alarms().size)
    }

    @Test fun globalAdmissionCoversTheRepositoryReadUntilCalibrationFinishes(): Unit = runBlocking {
        val coordinator = MaintenanceCoordinator(TestGenerations(7))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : TodoRepository by TodoRepositoryImpl(database) {
            override suspend fun getAllTodos(): List<Todo> {
                entered.complete(Unit)
                release.await()
                return TodoRepositoryImpl(database).getAllTodos()
            }
        }
        val work = async(start = CoroutineStart.UNDISPATCHED) { worker(coordinator, repository).doWork() }
        entered.await()
        val ready = CompletableDeferred<Unit>()
        val maintenance = async(start = CoroutineStart.UNDISPATCHED) { coordinator.withSession { ready.complete(Unit) } }
        assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
        assertFalse(ready.isCompleted)
        release.complete(Unit)
        assertEquals(ListenableWorker.Result.success(), work.await())
        maintenance.await()
        assertEquals(MaintenanceState.IDLE, coordinator.state.value)
    }

    @Test fun globalAdmissionPrecedesWaitingForAModuleLock(): Unit = runBlocking {
        val coordinator = MaintenanceCoordinator(TestGenerations(7))
        val release = CompletableDeferred<Unit>()
        val moduleOwner = async(start = CoroutineStart.UNDISPATCHED) { TodoOperationCoordinator.run { release.await() } }
        val work = async(start = CoroutineStart.UNDISPATCHED) { worker(coordinator).doWork() }
        val maintenance = async(start = CoroutineStart.UNDISPATCHED) { coordinator.withSession {} }
        assertEquals(MaintenanceState.DRAINING, coordinator.state.value)
        release.complete(Unit)
        moduleOwner.await()
        assertEquals(ListenableWorker.Result.success(), work.await())
        maintenance.await()
    }

    @Test fun cancellationIsNotConvertedIntoAnOrdinaryRetry(): Unit = runBlocking {
        val generations = TestGenerations(7)
        generations.currentFailure = CancellationException("synthetic cancellation")
        val coordinator = MaintenanceCoordinator(generations)
        assertFailsWith<CancellationException> { worker(coordinator).doWork() }
        generations.currentFailure = null
        coordinator.withSession { /* No leaked capture. */ }
    }

    private fun alarms() = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun worker(coordinator: MaintenanceCoordinator, todos: TodoRepository = TodoRepositoryImpl(database), settings: ReminderTestSettings = ReminderTestSettings()): ReminderReconciliationWorker {
        val context = RuntimeEnvironment.getApplication()
        // These Worker admission fixtures never commit; the dedicated integration tests use Room metadata.
        val state = ReminderSchedulingState(TestGenerations(7), settings)
        val reconciler = ReminderReconciler(
            todos, ReminderScheduler(context) { state },
            ScheduleRepositoryImpl(database), ScheduleReminderScheduler(context, { state }, { ScheduleRepositoryImpl(database) }),
            SubscriptionRepositoryImpl(database), SubscriptionReminderScheduler(context) { state }, coordinator, settings, ReminderNotifications(context),
        )
        return ReminderReconciliationWorker(context, parameters()) { reconciler }
    }

    private fun parameters(): WorkerParameters {
        val executor = Executor { it.run() }
        return WorkerParameters(
            UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor),
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
            },
            object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data) =
                    androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused progress" }
            },
            object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo) =
                    androidx.concurrent.futures.CallbackToFutureAdapter.getFuture<Void> { it.set(null); "unused foreground" }
            },
        )
    }
}
