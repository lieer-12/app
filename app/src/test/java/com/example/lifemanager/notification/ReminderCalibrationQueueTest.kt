package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.StartStopToken
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.model.WorkGenerationalId
import androidx.work.impl.schedulers
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.LifeManagerApp
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.ScheduleRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.ui.common.TestGenerations
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real WorkManager persistence, Processor, Worker and Reconciler; OS scheduling is manual. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderCalibrationQueueTest {
    private lateinit var context: LifeManagerApp
    private lateinit var manager: WorkManagerImpl
    private lateinit var database: LifeManagerDatabase
    private val dispatcher = StandardTestDispatcher()
    private val firstSnapshotRead = CompletableDeferred<Unit>()
    private val releaseFirstPass = CompletableDeferred<Unit>()
    private val snapshots = mutableListOf<List<String>>()
    private val workerIds = mutableListOf<String>()
    private val workName = "reminder_reconciliation_immediate"

    @Before fun setup() {
        context = LifeManagerApp()
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = context
        }
        ReflectionHelpers.setField(context, "mBase", base)
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java)
            .allowMainThreadQueries().build()
        val coordinator = MaintenanceCoordinator(TestGenerations(7))
        val realTodos = TodoRepositoryImpl(database)
        val observedTodos = object : TodoRepository by realTodos {
            override suspend fun getAllTodos(): List<Todo> {
                val rows = realTodos.getAllTodos()
                snapshots.add(rows.map { it.title })
                if (snapshots.size == 1) {
                    // A owns admission and has read the old Room snapshot, but has not rearmed it.
                    firstSnapshotRead.complete(Unit)
                    releaseFirstPass.await()
                }
                return rows
            }
        }
        val reconciler = ReminderReconciler(
            observedTodos, ReminderScheduler(context),
            ScheduleRepositoryImpl(database), ScheduleReminderScheduler(context),
            SubscriptionRepositoryImpl(database), SubscriptionReminderScheduler(context), coordinator,
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != ReminderReconciliationWorker::class.java.name) return null
                workerIds.add(workerParameters.id.toString())
                return ReminderReconciliationWorker(appContext, workerParameters) { reconciler }
            }
        }
        val executor = Executor { it.run() }
        val configuration = Configuration.Builder()
            .setTaskExecutor(executor).setExecutor(executor)
            .setWorkerCoroutineContext(dispatcher).setWorkerFactory(factory).build()
        val taskExecutor = WorkManagerTaskExecutor(configuration.taskExecutor)
        // Same isolated WorkDatabase as TestWorkManagerImpl. Its default GreedyScheduler would
        // race manual Processor starts, so use WorkManager's existing scheduler factory seam.
        val workDatabase = WorkDatabase.create(context, taskExecutor.serialTaskExecutor, configuration.clock, true)
        manager = WorkManagerImpl(
            context, configuration, taskExecutor, workDatabase, schedulersCreator = schedulers(),
        )
        WorkManagerImpl.setDelegate(manager)
    }

    @After fun cleanup() {
        try { if (::manager.isInitialized) manager.close() }
        finally {
            WorkManagerImpl.setDelegate(null)
            if (::database.isInitialized) database.close()
        }
    }

    // KEEP loses B while A is RUNNING; REPLACE cancels A; merely storing B never proves a fresh pass.
    @Test fun timezoneEventDuringRunningCalibrationQueuesAndExecutesAFreshRoomPass(): Unit = runTest(dispatcher) {
        val todos = TodoRepositoryImpl(database)
        val old = Todo(title = "before event", dueAt = Instant.now().plusSeconds(3_600))
        val id = todos.saveTodo(old, emptyList())
        ReminderReconciliationWorker.enqueueImmediate(context)
        val first = work().single()
        try {
            start(first.id.toString())
            pumpUntil("A must actually read Room while RUNNING") { firstSnapshotRead.isCompleted }
            assertEquals(WorkInfo.State.RUNNING, state(first.id.toString()))
            assertEquals(listOf(listOf("before event")), snapshots)

            BootReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
            val queued = work()
            assertEquals(2, queued.size, "Event during RUNNING must persist a subsequent calibration")
            val next = queued.single { it.id != first.id }
            assertEquals(WorkInfo.State.RUNNING, state(first.id.toString()), "Event must not replace/cancel A")
            assertEquals(WorkInfo.State.BLOCKED, next.state, "B must wait for A to succeed")
            assertEquals(listOf(first.id.toString()), manager.workDatabase.dependencyDao().getPrerequisites(next.id.toString()))
            assertFalse(manager.workDatabase.dependencyDao().hasCompletedAllPrerequisites(next.id.toString()))

            // Controlled fixture update represents current data available to the next pass.
            // This sends the actual Boot event; it does not change the host timezone or clock.
            todos.saveTodo(old.copy(id = id, title = "after event"), emptyList())
            releaseFirstPass.complete(Unit)
            pumpUntil("A must succeed and unblock B") {
                state(first.id.toString()) == WorkInfo.State.SUCCEEDED && state(next.id.toString()) == WorkInfo.State.ENQUEUED
            }
            assertTrue(manager.workDatabase.dependencyDao().hasCompletedAllPrerequisites(next.id.toString()))
            start(next.id.toString())
            pumpUntil("B must execute successfully, not just exist in the queue") {
                state(next.id.toString()) == WorkInfo.State.SUCCEEDED
            }
            assertEquals(listOf(first.id.toString(), next.id.toString()), workerIds)
            assertEquals(listOf(listOf("before event"), listOf("after event")), snapshots)
            // WorkManager may also own framework alarms. Assert the actual business identity,
            // not the size of the entire application's AlarmManager queue.
            val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
                shadowOf(it.operation).savedIntent.dataString == "lifemanager://todo-reminder/$id"
            }
            assertEquals("after event", shadowOf(alarm.operation).savedIntent.getStringExtra(TodoReminderReceiver.EXTRA_TITLE))
        } finally {
            // Intended policy RED must still release and finish the real paused Worker.
            releaseFirstPass.complete(Unit)
            pumpUntil("A must finish before fixture teardown") { state(first.id.toString())?.isFinished == true }
        }
    }

    // APPEND would propagate a terminal prerequisite into new work instead of starting a fresh chain.
    @Test fun failedCalibrationDoesNotPoisonTheNextSystemEventQueue() {
        assertTerminalChainCanRestart(WorkInfo.State.FAILED)
    }

    @Test fun cancelledCalibrationDoesNotPoisonTheNextSystemEventQueue() {
        assertTerminalChainCanRestart(WorkInfo.State.CANCELLED)
    }

    private fun assertTerminalChainCanRestart(terminalState: WorkInfo.State) {
        ReminderReconciliationWorker.enqueueImmediate(context)
        val previous = work().single().id.toString()
        // Only the terminal-chain fixtures use DAO state control; no Worker execution is claimed.
        assertEquals(1, manager.workDatabase.workSpecDao().setState(terminalState, previous))
        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        val next = work().single { it.id.toString() != previous }
        assertEquals(WorkInfo.State.ENQUEUED, next.state, "Terminal old work must not block a new calibration")
        assertTrue(manager.workDatabase.dependencyDao().getPrerequisites(next.id.toString()).isEmpty())
        assertTrue(manager.workDatabase.dependencyDao().hasCompletedAllPrerequisites(next.id.toString()))
    }

    private fun work(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(workName).get(5, TimeUnit.SECONDS)

    private fun state(id: String): WorkInfo.State? = manager.workDatabase.workSpecDao().getState(id)

    private fun start(id: String) {
        val spec = assertNotNull(manager.workDatabase.workSpecDao().getWorkSpec(id))
        assertTrue(manager.processor.startWork(StartStopToken(WorkGenerationalId(id, spec.generation))))
    }

    private fun TestScope.pumpUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue(condition(), message)
    }
}
