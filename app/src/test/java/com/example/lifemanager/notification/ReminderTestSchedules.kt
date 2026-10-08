package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.repository.ScheduleRepository
import java.time.Instant
import kotlinx.coroutines.flow.flowOf

/** Admission tests use an eligible row; separate integration tests exercise the real Room DAO. */
internal class ReminderTestSchedules : ScheduleRepository {
    private val current = Schedule(id = 41, title = "schedule fixture", startAt = start,
        endAt = start.plusSeconds(3600), reminderMinutes = 15, timeZone = "UTC")
    override fun observeSchedules() = flowOf(listOf(current))
    override suspend fun getSchedules() = listOf(current)
    override suspend fun getExceptions(scheduleId: Long) = emptyList<ScheduleException>()
    override suspend fun saveSchedule(schedule: Schedule): Long = error("read-only fixture")
    override suspend fun saveException(exception: ScheduleException): Unit = error("read-only fixture")
    override suspend fun deleteSchedule(id: Long): Unit = error("read-only fixture")
    companion object { val start: Instant = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS) }
}
