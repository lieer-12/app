package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class ScheduleReminderReceiver(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runnerProvider: (Context) -> ReminderBroadcastRunner = {
        ReminderBroadcastRunner.fromApplication(it, dispatcher)
    },
    private val onBusy: (Context) -> Unit = { ReminderReconciliationWorker.enqueueImmediate(it) },
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (scheduleId != 0L && title.isNotBlank()) {
            val pendingResult = goAsync()
            startReminderBroadcast(context, runnerProvider, { pendingResult?.finish() }, onBusy) {
                ScheduleOperationCoordinator.run {
                    // Alarm generation, settings and Room qualification are the Task 6 follow-up.
                    NotificationHelper.showScheduleReminder(context, scheduleId, title)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TITLE = "schedule_title"
    }
}
