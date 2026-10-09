package com.example.lifemanager.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ReminderReconciliationWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val reconcilerProvider: (Context) -> ReminderReconciler = { context ->
        EntryPointAccessors.fromApplication(
            context.applicationContext, ReminderReconciliationEntryPoint::class.java,
        ).reminderReconciler()
    },
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            reconcilerProvider(applicationContext).reconcile()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "todo_reminder_reconciliation"
        private const val IMMEDIATE_WORK_NAME = "reminder_reconciliation_immediate"

        /**
         * Fresh serialized passes: an event arriving during RUNNING must not disappear.
         * No rejected broadcast payload or ID is replayed; each pass reads current Room data.
         */
        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ReminderReconciliationWorker>().build(),
            )
        }

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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderReconciliationEntryPoint {
    fun reminderReconciler(): ReminderReconciler
}
