package com.example.lifemanager.notification

import android.content.Context
import com.example.lifemanager.domain.maintenance.DataGeneration

/** Operational hint for an undelivered occurrence. Never authorizes delivery without Room validation. */
internal class ScheduleAlarmOccurrenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("schedule_alarm_occurrences", Context.MODE_PRIVATE)
    fun pending(id: Long, generation: DataGeneration): Long? = synchronized(lock) {
        if (preferences.getLong("$id/generation", -1) != generation.value || !preferences.contains("$id/start")) null
        else preferences.getLong("$id/start", 0)
    }
    fun remember(id: Long, start: Long, generation: DataGeneration) = synchronized(lock) {
        check(preferences.edit().putLong("$id/start", start).putLong("$id/generation", generation.value).commit())
    }
    fun decided(id: Long, start: Long, generation: DataGeneration) = synchronized(lock) {
        if (pending(id, generation) == start) forget(id)
    }
    fun forget(id: Long) = synchronized(lock) {
        check(preferences.edit().remove("$id/start").remove("$id/generation").commit())
    }
    private companion object { val lock = Any() }
}
