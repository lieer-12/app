package com.example.lifemanager.notification

import android.content.Context

/** Operational cancellation hints, not business data. Missing cache never authorizes delivery. */
internal class ReminderAlarmInventory(context: Context, private val module: String) {
    private val preferences = context.getSharedPreferences("reminder_alarm_ids", Context.MODE_PRIVATE)
    fun ids(): Set<Long> = preferences.all.keys.filter { it.startsWith("$module/") }
        .mapNotNull { it.substringAfter('/').toLongOrNull() }.toSet()
    fun remember(id: Long) {
        check(preferences.edit().putBoolean("$module/$id", true).commit()) { "Unable to persist alarm identity" }
    }
    fun forget(id: Long) {
        check(preferences.edit().remove("$module/$id").commit()) { "Unable to remove alarm identity" }
    }
}
