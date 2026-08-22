package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (scheduleId != 0L && title.isNotBlank()) {
            NotificationHelper.showScheduleReminder(context, scheduleId, title)
        }
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TITLE = "schedule_title"
    }
}
