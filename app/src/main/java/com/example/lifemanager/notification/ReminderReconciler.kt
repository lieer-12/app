package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** Uses the application's repositories and global permit, never a separate database/coordinator. */
@Singleton
class ReminderReconciler @Inject constructor(
    private val todos: TodoRepository,
    private val todoScheduler: ReminderSchedulerContract,
    private val schedules: ScheduleRepository,
    private val scheduleScheduler: ScheduleReminderSchedulerContract,
    private val subscriptions: SubscriptionRepository,
    private val subscriptionScheduler: SubscriptionReminderSchedulerContract,
    private val coordinator: MaintenanceCoordinator,
    private val settings: SettingsRepository,
    private val notifications: ReminderNotifications,
) {
    suspend fun reconcile() {
        val generation = coordinator.capture()
        coordinator.run(generation) {
            val preferences = settings.getSettings()
            notifications.clearStale(generation)
            TodoOperationCoordinator.run {
                val current = todos.getAllTodos()
                todoScheduler.cancelObsolete(if (preferences.todoReminders) current.map { it.id }.toSet() else emptySet())
                if (!preferences.todoReminders) notifications.clearModule("todo")
                current.forEach { todo ->
                    val dueAt = todo.dueAt
                    if (!preferences.todoReminders || todo.isCompleted || dueAt == null) {
                        todoScheduler.cancel(todo.id)
                    } else todoScheduler.scheduleCurrent(todo.id, todo.title, dueAt)
                }
            }
            ScheduleOperationCoordinator.run {
                val current = schedules.getSchedules()
                scheduleScheduler.cancelObsolete(if (preferences.scheduleReminders) current.map { it.id }.toSet() else emptySet())
                if (!preferences.scheduleReminders) notifications.clearModule("schedule")
                current.forEach {
                    if (preferences.scheduleReminders) scheduleScheduler.scheduleCurrent(it)
                    else scheduleScheduler.cancel(it.id)
                }
            }
            SubscriptionOperationCoordinator.run {
                val current = subscriptions.getSubscriptions()
                subscriptionScheduler.cancelObsolete(if (preferences.subscriptionReminders) current.map { it.id }.toSet() else emptySet())
                if (!preferences.subscriptionReminders) notifications.clearModule("subscription")
                current.forEach {
                    if (preferences.subscriptionReminders) subscriptionScheduler.scheduleCurrent(it, subscriptions.getReminderDays(it.id))
                    else subscriptionScheduler.cancelAll(it.id)
                }
            }
        }
    }
}
