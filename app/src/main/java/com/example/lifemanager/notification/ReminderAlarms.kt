package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/** Both permission queries and exact scheduling can race a revocation. */
internal fun AlarmManager.setReminder(triggerAt: Long, operation: PendingIntent) {
    try {
        if (Build.VERSION.SDK_INT < 31 || canScheduleExactAlarms()) {
            setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            return
        }
    } catch (_: SecurityException) {
        // The inexact call itself can fail; let the workflow report/retry that failure.
    }
    setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
}
