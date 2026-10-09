package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lifemanager.domain.repository.ScheduleRepository
import com.example.lifemanager.domain.repository.SettingsRepository
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import com.example.lifemanager.domain.usecase.ScheduleOperationCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class ScheduleReminderReceiver(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runnerProvider: (Context) -> ReminderBroadcastRunner = {
        ReminderBroadcastRunner.fromApplication(it, dispatcher)
    },
    private val onBusy: (Context) -> Unit = { ReminderReconciliationWorker.enqueueImmediate(it) },
    private val repositoryProvider: (Context) -> ScheduleRepository = {
        EntryPointAccessors.fromApplication(it.applicationContext, ReminderSafetyEntryPoint::class.java).scheduleRepository()
    },
    private val settingsProvider: (Context) -> SettingsRepository = ::reminderSettings,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L)
        if (scheduleId > 0L && intent.hasExtra(EXTRA_OCCURRENCE_START) &&
            intent.dataString == "lifemanager://schedule-reminder/$scheduleId") {
            val generation = ReminderGeneration.read(intent)
            val occurrenceStart = intent.getLongExtra(EXTRA_OCCURRENCE_START, Long.MIN_VALUE)
            val pendingResult = goAsync()
            startReminderBroadcast(context, runnerProvider, { pendingResult?.finish() }, onBusy, generation) {
                try {
                    ScheduleOperationCoordinator.run {
                        val enabled = settingsProvider(context).getSettings().scheduleReminders
                        if (enabled) {
                            val repository = repositoryProvider(context)
                            val schedule = repository.getSchedules().firstOrNull { it.id == scheduleId }
                            if (schedule != null && ScheduleReminderEligibility.matches(schedule,
                                    repository.getExceptions(scheduleId), occurrenceStart, Instant.now()) &&
                                settingsProvider(context).getSettings().scheduleReminders) {
                                NotificationHelper.showScheduleReminder(context, scheduleId, schedule.title, requireNotNull(generation))
                            }
                        }
                        // Consume only after a successful decision, not an attempted repository read.
                        ScheduleAlarmOccurrenceStore(context).decided(scheduleId, occurrenceStart, requireNotNull(generation))
                    }
                } catch (error: Exception) {
                    try { onBusy(context.applicationContext) } catch (queueError: Exception) { error.addSuppressed(queueError) }
                    throw error
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TITLE = "schedule_title"
        const val EXTRA_OCCURRENCE_START = "schedule_occurrence_start"
    }
}
