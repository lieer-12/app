package com.example.lifemanager.notification

import android.content.Context
import com.example.lifemanager.domain.maintenance.MaintenanceReminderEffects
import com.example.lifemanager.domain.maintenance.ReminderIdentities
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class AndroidMaintenanceReminderEffects @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val todos: ReminderSchedulerContract,
    private val schedules: ScheduleReminderSchedulerContract,
    private val subscriptions: SubscriptionReminderSchedulerContract,
    private val reconciler: ReminderReconciler,
    private val notifications: ReminderNotifications,
) : MaintenanceReminderEffects {
    /** Caller owns maintenance admission. Never attempt ordinary admission inside the session. */
    override suspend fun clearPrevious(identities: ReminderIdentities) {
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        TodoOperationCoordinator.run {
            identities.todoIds.forEach { id ->
                attempt { todos.cancel(id) }
                attempt { notifications.cancelTodo(id) }
            }
        }
        ScheduleOperationCoordinator.run {
            identities.scheduleIds.forEach { id ->
                attempt { schedules.cancel(id) }
                attempt { notifications.cancelSchedule(id) }
            }
        }
        SubscriptionOperationCoordinator.run {
            identities.subscriptionIds.forEach { id ->
                attempt { subscriptions.cancelAll(id) }
                attempt { SubscriptionNotificationHelper.cancelAll(context, id) }
            }
        }
        failure?.let { throw it }
    }

    /** Must be called after the maintenance/ordinary permit has been released. */
    override suspend fun reconcile() = reconciler.reconcile()

    override fun requestReconciliation() = ReminderReconciliationWorker.enqueueImmediate(context)
}
