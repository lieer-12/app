package com.example.lifemanager.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in supportedActions) return
        try {
            // Durable work reads current Room data under the application's global permit.
            // No broadcast-lifetime database reads, alarm loop, or waiting for maintenance.
            ReminderReconciliationWorker.enqueueImmediate(context.applicationContext)
        } catch (_: Exception) {
            Log.w("BootReceiver", "Unable to enqueue reminder calibration; daily work remains available")
        }
    }

    companion object {
        private val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
