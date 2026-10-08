package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.repository.ScheduleRepository
import dagger.hilt.android.EntryPointAccessors
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.usecase.ScheduleRules
import java.time.Instant

class ScheduleReminderScheduler(
    private val context: Context,
    private val stateProvider: () -> ReminderSchedulingState = { ReminderSchedulingState.fromApplication(context) },
    private val repositoryProvider: () -> ScheduleRepository = {
        EntryPointAccessors.fromApplication(context.applicationContext, ReminderSafetyEntryPoint::class.java).scheduleRepository()
    },
    private val nowProvider: () -> Instant = Instant::now,
) : ScheduleReminderSchedulerContract {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val inventory = ReminderAlarmInventory(context, "schedule")
    private val occurrences = ScheduleAlarmOccurrenceStore(context)

    override suspend fun scheduleCurrent(schedule: Schedule) {
        val (generation, settings) = stateProvider().read()
        val pending = occurrences.pending(schedule.id, generation)
        if (!settings.scheduleReminders) {
            cancel(schedule.id)
            ReminderNotifications(context).clearModule("schedule")
            return
        }
        val now = nowProvider()
        val exceptions = repositoryProvider().getExceptions(schedule.id)
        val start = if (pending != null && ScheduleReminderEligibility.matches(schedule, exceptions, pending, now)) pending
            else ScheduleRules.nextReminderOccurrence(schedule, now, exceptions)?.startAt?.toEpochMilli()
        if (start == null) { cancel(schedule.id); return }
        val triggerAt = maxOf(start - (schedule.reminderMinutes ?: 0) * 60_000L, now.toEpochMilli() + 1_000)
        inventory.remember(schedule.id)
        occurrences.remember(schedule.id, start, generation)
        cancelAlarm(schedule.id) // Keep the qualified hint until replacement succeeds or Room rejects it.
        val pendingIntent = pendingIntent(schedule.id, schedule.title, generation, start)
        try { alarmManager.setReminder(triggerAt, pendingIntent) }
        catch (error: Exception) {
            try { cancelAlarm(schedule.id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun cancel(scheduleId: Long) {
        cancelAlarm(scheduleId)
        inventory.forget(scheduleId)
        occurrences.forget(scheduleId)
    }

    private fun cancelAlarm(scheduleId: Long) {
        listOf(false, true).forEach { current ->
            val intent = Intent(context, ScheduleReminderReceiver::class.java)
            if (current) intent.data = Uri.parse("lifemanager://schedule-reminder/$scheduleId")
            PendingIntent.getBroadcast(context, ReminderKey.forSchedule(scheduleId), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }

    override fun cancelObsolete(currentIds: Set<Long>) {
        (inventory.ids() - currentIds).forEach { cancel(it); ReminderNotifications(context).cancelSchedule(it) }
    }

    private fun pendingIntent(scheduleId: Long, title: String, generation: DataGeneration, occurrenceStart: Long): PendingIntent {
        val intent = Intent(context, ScheduleReminderReceiver::class.java)
            .setData(Uri.parse("lifemanager://schedule-reminder/$scheduleId"))
            .putExtra(ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            .putExtra(ScheduleReminderReceiver.EXTRA_TITLE, title)
            .putExtra(ScheduleReminderReceiver.EXTRA_OCCURRENCE_START, occurrenceStart)
            .putExtra(ReminderGeneration.EXTRA, generation.value)
        return PendingIntent.getBroadcast(
            context,
            ReminderKey.forSchedule(scheduleId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
