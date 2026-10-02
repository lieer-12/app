package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.repository.SubscriptionRepository
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: TodoRepository
    @Inject lateinit var reminderScheduler: ReminderSchedulerContract
    @Inject lateinit var scheduleRepository: ScheduleRepository
    @Inject lateinit var scheduleReminderScheduler: ScheduleReminderSchedulerContract
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var subscriptionReminderScheduler: SubscriptionReminderSchedulerContract

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.getAllTodos()
                    .filter { !it.isCompleted && it.dueAt != null }
                    .forEach { todo -> reminderScheduler.schedule(todo.id, todo.title, todo.dueAt ?: Instant.now()) }
                scheduleRepository.getSchedules().forEach(scheduleReminderScheduler::schedule)
                SubscriptionOperationCoordinator.run {
                    subscriptionRepository.getSubscriptions().forEach { subscription ->
                        subscriptionReminderScheduler.schedule(subscription, subscriptionRepository.getReminderDays(subscription.id))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("BootReceiver", "Unable to restore reminders; daily reconciliation will retry", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
