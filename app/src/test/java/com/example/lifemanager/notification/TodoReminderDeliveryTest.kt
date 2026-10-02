package com.example.lifemanager.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.atomic.AtomicBoolean
import com.example.lifemanager.domain.repository.TodoRepository
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodoReminderDeliveryTest {
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: TodoRepositoryImpl
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java).build()
        repository = TodoRepositoryImpl(database)
    }
    @After fun cleanup() { database.close() }

    @Test fun deletedTodoDoesNotNotify(): Unit = runTest {
        val due = Instant.now().plusSeconds(600)
        val id = repository.saveTodo(Todo(title = "任务", dueAt = due), emptyList())
        repository.deleteTodo(id)
        receive(id, due)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }
    @Test fun completedTodoDoesNotNotify(): Unit = runTest {
        val due = Instant.now().plusSeconds(600)
        val id = repository.saveTodo(Todo(title = "任务", dueAt = due, isCompleted = true), emptyList())
        receive(id, due)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }
    @Test fun changedDeadlineRejectsQueuedOldBroadcast(): Unit = runTest {
        val due = Instant.now().plusSeconds(600)
        val id = repository.saveTodo(Todo(title = "任务", dueAt = due.plusSeconds(3600)), emptyList())
        receive(id, due)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }
    @Test fun reminderBeforeFifteenMinuteWindowDoesNotNotify(): Unit = runTest {
        val due = Instant.now().plusSeconds(3600)
        val id = repository.saveTodo(Todo(title = "任务", dueAt = due), emptyList())
        receive(id, due)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }
    @Test fun expiredReminderDoesNotNotify(): Unit = runTest {
        val due = Instant.now().minusSeconds(60)
        val id = repository.saveTodo(Todo(title = "任务", dueAt = due), emptyList())
        receive(id, due)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }
    @Test fun validReminderUsesLatestDatabaseTitle(): Unit = runTest {
        val due = Instant.now().plusSeconds(600)
        val id = repository.saveTodo(Todo(title = "数据库的新标题", dueAt = due), emptyList())
        receive(id, due)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(1, notifications.size())
        assertEquals("数据库的新标题", notifications.allNotifications.single().extras.getCharSequence("android.text"))
    }
    @Test fun deliveryWaitsForInFlightTodoMutation(): Unit = runTest {
        val due = Instant.now().plusSeconds(600)
        val id = repository.saveTodo(Todo(title = "旧标题", dueAt = due), emptyList())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = backgroundScope.launch {
            TodoOperationCoordinator.run {
                entered.complete(Unit)
                release.await()
                repository.setCompleted(id, true)
            }
        }
        runCurrent()
        entered.await()
        val readCompleted = AtomicBoolean()
        try {
            startReceive(id, due, readCompleted)
            runCurrent()
            kotlin.test.assertFalse(readCompleted.get())
            assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        } finally { release.complete(Unit) }
        awaitRead(readCompleted)
        mutation.join()
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.receive(id: Long, due: Instant) {
        val readCompleted = AtomicBoolean()
        startReceive(id, due, readCompleted)
        awaitRead(readCompleted)
    }

    private fun kotlinx.coroutines.test.TestScope.startReceive(id: Long, due: Instant, readCompleted: AtomicBoolean) {
        val observedRepository = object : TodoRepository by repository {
            override suspend fun getAllTodos(): List<Todo> = repository.getAllTodos().also { readCompleted.set(true) }
        }
        val receiver = TodoReminderReceiver({ observedRepository }, StandardTestDispatcher(testScheduler))
        receiver.onReceive(context, Intent().setData(Uri.parse("lifemanager://todo-reminder/$id"))
            .putExtra("todo_id", id).putExtra("todo_title", "旧标题").putExtra("todo_due_at", due.toEpochMilli()))
    }

    private fun kotlinx.coroutines.test.TestScope.awaitRead(readCompleted: AtomicBoolean) {
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            runCurrent() // Run Room continuations without advancing the receiver timeout's virtual clock.
            if (readCompleted.get()) break
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        kotlin.test.assertTrue(readCompleted.get(), "Receiver must finish reading the real Room repository")
    }
}
