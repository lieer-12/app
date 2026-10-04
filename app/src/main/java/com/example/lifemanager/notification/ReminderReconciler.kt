package com.example.lifemanager.notification

import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.repository.TodoRepository
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
) {
    suspend fun reconcile() {
        val generation = coordinator.capture()
        coordinator.run(generation) {
            TodoOperationCoordinator.run {
                todos.getAllTodos().forEach { todo ->
                    val dueAt = todo.dueAt
                    if (todo.isCompleted || dueAt == null) todoScheduler.cancel(todo.id)
                    else todoScheduler.schedule(todo.id, todo.title, dueAt)
                }
            }
            ScheduleOperationCoordinator.run {
                schedules.getSchedules().forEach(scheduleScheduler::schedule)
            }
            SubscriptionOperationCoordinator.run {
                subscriptions.getSubscriptions().forEach {
                    subscriptionScheduler.schedule(it, subscriptions.getReminderDays(it.id))
                }
            }
        }
    }
}
