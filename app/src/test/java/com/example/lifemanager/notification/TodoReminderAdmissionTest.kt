package com.example.lifemanager.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TodoReminderAdmissionTest {
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: TodoRepositoryImpl
    private lateinit var admission: ReminderAdmissionFixture
    private val context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = TodoRepositoryImpl(database)
        admission = ReminderAdmissionFixture()
    }

    @After fun cleanup() { database.close() }

    // Missing global admission must fail on repository access/notification behavior, not a new API.
    @Test fun readyMaintenanceRejectsDeliveryWithoutReadingOrPosting(): Unit = runTest {
        val todo = saveDueTodo()
        val reads = ObservedReads(repository)
        admission.coordinator.withSession {
            assertEquals(MaintenanceState.READY, admission.coordinator.state.value)
            receive(todo, reads)
            settleReads(reads)
            assertRejected(reads)
            assertEquals(1, admission.calibrationRequests)
            assertEquals(MaintenanceState.READY, admission.coordinator.state.value)
        }
        settleReads(reads)
        assertRejected(reads)
        assertEquals(1, admission.calibrationRequests)
    }

    @Test fun drainingMaintenanceRejectsNewDeliveryBeforeItsExistingOperationFinishes(): Unit = runTest {
        val todo = saveDueTodo()
        val reads = ObservedReads(repository)
        val token = admission.coordinator.capture()
        val release = CompletableDeferred<Unit>()
        val existing = backgroundScope.async { admission.coordinator.run(token) { release.await() } }
        runCurrent()
        val maintenance = backgroundScope.async { admission.coordinator.withSession {} }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, admission.coordinator.state.value)
            receive(todo, reads)
            settleReads(reads)
            assertRejected(reads)
        } finally {
            release.complete(Unit)
            existing.await()
            maintenance.cancelAndJoin()
            settleReads(reads)
        }
        assertRejected(reads)
    }

    // Capturing only after IO dispatch would incorrectly replay this arrival under the same generation.
    @Test fun cancelledMaintenanceDoesNotReplayAnArrivalButAllowsAFreshBroadcast(): Unit = runTest {
        val todo = saveDueTodo()
        val reads = ObservedReads(repository)
        assertFailsWith<CancellationException> {
            admission.coordinator.withSession {
                receive(todo, reads)
                throw CancellationException("synthetic confirmation cancellation")
            }
        }
        assertEquals(MaintenanceState.IDLE, admission.coordinator.state.value)
        assertEquals(DataGeneration(37), admission.generations.current())
        settleReads(reads)
        assertRejected(reads)
        assertEquals(1, admission.calibrationRequests)

        receive(todo, reads)
        settleReads(reads)
        assertEquals(1, reads.started.get())
        assertEquals(1, notifications.size())
        assertEquals("current fixture", notifications.allNotifications.single().extras.getCharSequence("android.text"))
        assertEquals(1, admission.calibrationRequests)
    }

    // Recapturing after dispatch would attach an old arrival to the restored entity with the same ID/due time.
    @Test fun undispatchedOldBroadcastCannotReadOrNotifyTheRestoredSameIdTodo(): Unit = runTest {
        val todo = saveDueTodo()
        val reads = ObservedReads(repository)
        receive(todo, reads)
        assertEquals(0, reads.started.get(), "IO work must still be queued")
        admission.coordinator.withSession { session ->
            admission.coordinator.withMaintenance(session) {
                admission.generations.commit(session.generation) {
                    // Synchronous isolated-Room mutation keeps receiver IO queued through the commit.
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE todos SET title = ? WHERE id = ?",
                        arrayOf<Any>("restored fixture", todo.id),
                    )
                }
            }
        }
        assertEquals(DataGeneration(38), admission.generations.current())
        assertEquals(0, reads.started.get(), "Commit must precede receiver IO dispatch")
        settleReads(reads)
        assertRejected(reads)
        assertEquals(0, admission.calibrationRequests, "Stale rejection must not enqueue calibration")
        assertEquals("restored fixture", repository.getAllTodos().single().title)
    }

    // Admission must cover the repository read through the notification, so maintenance drains it.
    @Test fun maintenanceDrainsAnAlreadyAdmittedRepositoryRead(): Unit = runTest {
        val todo = saveDueTodo()
        val releaseRead = CompletableDeferred<Unit>()
        val reads = ObservedReads(repository) { releaseRead.await() }
        receive(todo, reads)
        runCurrent()
        assertEquals(1, reads.started.get())
        val ready = CompletableDeferred<Unit>()
        val maintenance = backgroundScope.async {
            admission.coordinator.withSession { ready.complete(Unit) }
        }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, admission.coordinator.state.value)
            assertFalse(ready.isCompleted, "Maintenance must wait for receiver validation and posting")
            assertEquals(0, notifications.size())
        } finally {
            releaseRead.complete(Unit)
            settleReads(reads)
            maintenance.await()
        }
        assertEquals(1, notifications.size())
        assertEquals(MaintenanceState.IDLE, admission.coordinator.state.value)
    }

    // Acquiring the module lock first would let maintenance become READY while delivery waits on it.
    @Test fun globalAdmissionPrecedesWaitingForTheTodoModuleLock(): Unit = runTest {
        val todo = saveDueTodo()
        val reads = ObservedReads(repository)
        val releaseModule = CompletableDeferred<Unit>()
        val moduleOwner = backgroundScope.async { TodoOperationCoordinator.run { releaseModule.await() } }
        runCurrent()
        receive(todo, reads)
        runCurrent()
        val maintenance = backgroundScope.async { admission.coordinator.withSession {} }
        try {
            runCurrent()
            assertEquals(MaintenanceState.DRAINING, admission.coordinator.state.value)
            assertEquals(0, reads.started.get())
            assertEquals(0, notifications.size())
        } finally {
            releaseModule.complete(Unit)
            moduleOwner.await()
            settleReads(reads)
            maintenance.await()
        }
        assertEquals(1, reads.started.get())
        assertEquals(1, notifications.size())
        assertEquals(MaintenanceState.IDLE, admission.coordinator.state.value)
    }

    @Test fun eightSecondTimeoutReleasesTheStalledDeliveryForMaintenance(): Unit = runTest {
        val todo = saveDueTodo()
        val releaseRead = CompletableDeferred<Unit>()
        val reads = ObservedReads(repository) { releaseRead.await() }
        try {
            receive(todo, reads)
            runCurrent()
            assertEquals(1, reads.started.get())
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals(1, reads.finished.get(), "Timed out validation must be cancelled")
            admission.coordinator.withSession {
                assertEquals(MaintenanceState.READY, admission.coordinator.state.value)
                assertEquals(0, notifications.size())
            }
        } finally {
            releaseRead.complete(Unit)
            settleReads(reads)
        }
        assertEquals(MaintenanceState.IDLE, admission.coordinator.state.value)
    }

    @Test fun idleSourcePayloadStillDeliversFromRealRoomAtANonzeroGeneration(): Unit = runTest {
        val todo = saveDueTodo()
        val reads = ObservedReads(repository)
        receive(todo, reads)
        settleReads(reads)
        assertEquals(1, reads.started.get())
        assertEquals(1, notifications.size())
        assertEquals("current fixture", notifications.allNotifications.single().extras.getCharSequence("android.text"))
        assertEquals(DataGeneration(37), admission.generations.current())
    }

    private suspend fun saveDueTodo(): Todo {
        val todo = Todo(title = "current fixture", dueAt = Instant.now().plusSeconds(600))
        return todo.copy(id = repository.saveTodo(todo, emptyList()))
    }

    private fun TestScope.receive(todo: Todo, reads: ObservedReads) {
        admission.todoReceiver(reads.repository, StandardTestDispatcher(testScheduler)).onReceive(
            context,
            Intent().setData(Uri.parse("lifemanager://todo-reminder/${todo.id}"))
                .putExtra(ReminderGeneration.EXTRA, 37L)
                .putExtra(TodoReminderReceiver.EXTRA_TODO_ID, todo.id)
                .putExtra(TodoReminderReceiver.EXTRA_DUE_AT, todo.dueAt!!.toEpochMilli())
                .putExtra(TodoReminderReceiver.EXTRA_TITLE, "untrusted fixture"),
        )
    }

    private fun assertRejected(reads: ObservedReads) {
        // Notification first also detects an accidental post without any repository validation.
        assertEquals(0, notifications.size(), "Rejected arrival must not post a notification")
        assertEquals(0, reads.started.get(), "Rejected arrival must not read current Todo rows")
        assertEquals(0, admission.repositoryResolutions, "Rejected arrival must not resolve its repository")
    }

    private fun TestScope.settleReads(reads: ObservedReads) {
        // Room uses real executors. Drain their continuations without advancing the timeout clock.
        runCurrent()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (reads.finished.get() < reads.started.get() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            runCurrent()
        }
        runCurrent()
        assertEquals(reads.started.get(), reads.finished.get(), "Receiver repository work must finish before closing Room")
    }

    private class ObservedReads(delegate: TodoRepository, beforeRead: suspend () -> Unit = {}) {
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val repository = object : TodoRepository by delegate {
            override suspend fun getAllTodos(): List<Todo> {
                started.incrementAndGet()
                try {
                    beforeRead()
                    return delegate.getAllTodos()
                } finally { finished.incrementAndGet() }
            }
        }
    }
}

/** Test-only fixture: every session and receiver use this exact coordinator/store. */
internal class ReminderAdmissionFixture {
    val generations = ReminderAdmissionGenerations()
    val coordinator = MaintenanceCoordinator(generations)
    val settings = ReminderTestSettings()
    val scheduling = ReminderSchedulingState(generations, settings)
    var repositoryResolutions = 0
        private set
    var calibrationRequests = 0
        private set

    // Main observed behavioral RED with the old constructor before this service injection.
    fun todoReceiver(repository: TodoRepository, dispatcher: CoroutineDispatcher): TodoReminderReceiver =
        TodoReminderReceiver(
            { repositoryResolutions++; repository }, dispatcher,
            { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrationRequests++ },
            settingsProvider = { settings },
        )

    fun scheduleReceiver(dispatcher: CoroutineDispatcher): ScheduleReminderReceiver =
        ScheduleReminderReceiver(
            dispatcher, { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrationRequests++ },
            repositoryProvider = { ReminderTestSchedules() }, settingsProvider = { settings },
        )
}

internal class ReminderAdmissionGenerations : DataGenerationRepository {
    private val values = MutableStateFlow(DataGeneration(37))
    override fun observe() = values
    override suspend fun current() = values.value
    override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration {
        if (values.value != expected) throw StaleGenerationException()
        mutation()
        return DataGeneration(expected.value + 1).also { values.value = it }
    }
}
