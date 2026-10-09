package com.example.lifemanager.data.local

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.ScheduleRepositoryImpl
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.notification.ReminderSchedulerContract
import com.example.lifemanager.notification.ScheduleReminderSchedulerContract
import com.example.lifemanager.notification.SubscriptionReminderSchedulerContract
import com.example.lifemanager.ui.schedule.ScheduleViewModel
import com.example.lifemanager.ui.subscription.SubscriptionViewModel
import com.example.lifemanager.ui.todo.TodoViewModel
import com.example.lifemanager.ui.common.GenerationAccess
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Isolated real Room consumers; replacement is a synthetic transaction, not SAF restore. */
@RunWith(AndroidJUnit4::class)
class BusinessMaintenanceConsumerDeviceTest {
    @Test fun todoReplacementInvalidatesDraftWithoutEditingRestoredSameId(): Unit = runBlocking {
        val database = database()
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val repository = TodoRepositoryImpl(database)
        repository.saveTodo(Todo(title = "synthetic original"), emptyList())
        val model = TodoViewModel(repository, TodoAlarms(), Dispatchers.IO, GenerationAccess(generations, coordinator))
        try {
            val loaded = withTimeout(10_000) { model.uiState.first { !it.isLoading && it.todos.isNotEmpty() } }
            withContext(Dispatchers.Main.immediate) {
                model.openEditor(loaded.todos.single(), loaded.generation)
                model.onTitleChanged("synthetic old draft")
            }
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    repository.saveTodo(loaded.todos.single().copy(title = "synthetic restored"), emptyList())
                }
            } }
            withTimeout(10_000) { model.uiState.first { it.generation == DataGeneration(1) && it.todos.singleOrNull()?.title == "synthetic restored" && !it.isMaintaining } }
            awaitActions(model) {
                model.openEditor(loaded.todos.single(), loaded.generation)
                model.toggleTodo(loaded.todos.single(), loaded.generation)
                model.deleteTodo(loaded.todos.single().id, loaded.generation)
            }
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals("synthetic restored", repository.getAllTodos().single().title)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun scheduleReplacementInvalidatesDraftWithoutEditingRestoredSameId(): Unit = runBlocking {
        val database = database()
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val repository = ScheduleRepositoryImpl(database)
        val start = Instant.now().plusSeconds(3600)
        repository.saveSchedule(Schedule(title = "synthetic original", startAt = start,
            endAt = start.plusSeconds(3600), timeZone = "UTC"))
        val model = ScheduleViewModel(repository, ScheduleAlarms(), Dispatchers.IO, GenerationAccess(generations, coordinator))
        try {
            val loaded = withTimeout(10_000) { model.uiState.first { !it.isLoading && it.schedules.isNotEmpty() } }
            withContext(Dispatchers.Main.immediate) {
                model.openEditor(loaded.schedules.single(), loaded.generation)
                model.onTitleChanged("synthetic old draft")
            }
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    repository.saveSchedule(loaded.schedules.single().copy(title = "synthetic restored"))
                }
            } }
            withTimeout(10_000) { model.uiState.first { it.generation == DataGeneration(1) && it.schedules.singleOrNull()?.title == "synthetic restored" && !it.isMaintaining } }
            awaitActions(model) {
                model.openEditor(loaded.schedules.single(), loaded.generation)
                model.deleteSchedule(loaded.schedules.single().id, loaded.generation)
            }
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals("synthetic restored", repository.getSchedules().single().title)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun subscriptionReplacementInvalidatesDraftWithoutEditingRestoredSameId(): Unit = runBlocking {
        val database = database()
        val generations = DataGenerationRepositoryImpl(database)
        val coordinator = MaintenanceCoordinator(generations)
        val repository = SubscriptionRepositoryImpl(database)
        val today = LocalDate.now()
        repository.saveSubscription(Subscription(appName = "synthetic original", amountMinor = 100,
            billingCycle = BillingCycle.MONTHLY, nextBillingDate = today, startDate = today,
            createdAt = Instant.now(), updatedAt = Instant.now()), emptySet())
        val model = SubscriptionViewModel(repository, SubscriptionAlarms(), SettingsRepositoryImpl(database), Dispatchers.IO, GenerationAccess(generations, coordinator))
        try {
            val loaded = withTimeout(10_000) { model.uiState.first { !it.isLoading && it.subscriptions.isNotEmpty() } }
            withContext(Dispatchers.Main.immediate) { model.openEditor(loaded.subscriptions.single(), loaded.generation) }
            withTimeout(10_000) { model.uiState.first { !it.editor.isLoadingReminders } }
            coordinator.withSession { session -> coordinator.withMaintenance(session) {
                generations.commit(session.generation) {
                    repository.saveSubscription(loaded.subscriptions.single().copy(appName = "synthetic restored"), emptySet())
                }
            } }
            withTimeout(10_000) { model.uiState.first { it.generation == DataGeneration(1) && it.subscriptions.singleOrNull()?.appName == "synthetic restored" && !it.isMaintaining } }
            awaitActions(model) {
                model.openEditor(loaded.subscriptions.single(), loaded.generation)
                model.cancelSubscription(loaded.subscriptions.single().id, loaded.generation)
                model.deleteSubscription(loaded.subscriptions.single().id, loaded.generation)
            }
            assertFalse(model.uiState.value.editor.isOpen)
            assertEquals("synthetic restored", repository.getSubscriptions().single().appName)
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            database.close()
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), LifeManagerDatabase::class.java,
    ).addCallback(LifeManagerDatabase.INITIALIZE).build()

    // A rejected callback must not merely have an unexecuted IO job when the assertion runs.
    private suspend fun awaitActions(model: ViewModel, actions: () -> Unit) {
        val jobs = withContext(Dispatchers.Main.immediate) {
            val existing = model.viewModelScope.coroutineContext.job.children.toSet()
            actions()
            model.viewModelScope.coroutineContext.job.children.filterNot(existing::contains).toList()
        }
        withTimeout(10_000) { jobs.forEach { it.join() } }
    }

    // Only Android alarm service is replaced. Room, repositories, ViewModels and coordinator are real.
    private class TodoAlarms : ReminderSchedulerContract {
        override fun schedule(todoId: Long, title: String, dueAt: Instant) = Unit
        override fun cancel(todoId: Long) = Unit
    }
    private class ScheduleAlarms : ScheduleReminderSchedulerContract {
        override fun schedule(schedule: Schedule) = Unit
        override fun cancel(scheduleId: Long) = Unit
    }
    private class SubscriptionAlarms : SubscriptionReminderSchedulerContract {
        override fun schedule(subscription: Subscription, reminderDays: Set<Int>) = Unit
        override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) = Unit
        override fun cancelAll(subscriptionId: Long) = Unit
    }
}
