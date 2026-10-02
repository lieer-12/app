package com.example.lifemanager.notification

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.toDomain
import com.example.lifemanager.domain.usecase.SubscriptionOperationCoordinator
import com.example.lifemanager.domain.usecase.TodoOperationCoordinator
import java.time.Instant
import java.util.concurrent.TimeUnit

class ReminderReconciliationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val database = Room.databaseBuilder(
            applicationContext,
            LifeManagerDatabase::class.java,
            "life-manager.db",
        ).addMigrations(*LifeManagerDatabase.MIGRATIONS).build()
        return try {
            val scheduler = ReminderScheduler(applicationContext)
            val scheduleScheduler = ScheduleReminderScheduler(applicationContext)
            val subscriptionScheduler = SubscriptionReminderScheduler(applicationContext)
            TodoOperationCoordinator.run {
                database.todoDao().getAll().forEach { todo ->
                    val dueAt = todo.dueAt
                    if (todo.isCompleted || dueAt == null) scheduler.cancel(todo.id)
                    else scheduler.schedule(todo.id, todo.title, Instant.ofEpochMilli(dueAt))
                }
            }
            database.scheduleDao().getAll().forEach { scheduleScheduler.schedule(it.toDomain()) }
            SubscriptionOperationCoordinator.run {
                val subscriptionDao = database.subscriptionDao()
                subscriptionDao.getAll().forEach { subscription ->
                    subscriptionScheduler.schedule(subscription.toDomain(), subscriptionDao.getReminderDays(subscription.id).toSet())
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            database.close()
        }
    }

    companion object {
        private const val WORK_NAME = "todo_reminder_reconciliation"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderReconciliationWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
