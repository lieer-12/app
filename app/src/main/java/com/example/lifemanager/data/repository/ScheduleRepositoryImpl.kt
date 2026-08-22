package com.example.lifemanager.data.repository

import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import com.example.lifemanager.domain.repository.ScheduleRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ScheduleRepositoryImpl @Inject constructor(
    private val database: LifeManagerDatabase,
) : ScheduleRepository {
    private val dao = database.scheduleDao()

    override fun observeSchedules(): Flow<List<Schedule>> = dao.observeAll().map { entities ->
        entities.map(ScheduleEntity::toDomain)
    }

    override suspend fun getSchedules(): List<Schedule> = dao.getAll().map(ScheduleEntity::toDomain)

    override suspend fun getExceptions(scheduleId: Long): List<ScheduleException> =
        dao.getExceptions(scheduleId).map(ScheduleExceptionEntity::toDomain)

    override suspend fun saveSchedule(schedule: Schedule): Long {
        val now = Instant.now()
        val entity = schedule.toEntity(now)
        return dao.upsert(entity).let { inserted -> if (schedule.id == 0L) inserted else schedule.id }
    }

    override suspend fun saveException(exception: ScheduleException) {
        dao.upsertException(exception.toEntity())
    }

    override suspend fun deleteSchedule(id: Long) {
        dao.deleteById(id)
    }
}

fun ScheduleEntity.toDomain(): Schedule = Schedule(
    id = id,
    title = title,
    startAt = startAt?.let(Instant::ofEpochMilli),
    endAt = endAt?.let(Instant::ofEpochMilli),
    isAllDay = isAllDay,
    allDayStartDate = allDayStartDate?.let(LocalDate::ofEpochDay),
    allDayEndDate = allDayEndDate?.let(LocalDate::ofEpochDay),
    location = location,
    participants = participants,
    note = note,
    color = color,
    reminderMinutes = reminderMinutes,
    repeatRule = repeatRule,
    repeatInterval = repeatInterval,
    repeatDaysOfWeek = repeatDaysOfWeek.orEmpty().split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet(),
    repeatEndDate = repeatEndDate?.let(LocalDate::ofEpochDay),
    timeZone = timeZone,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

private fun Schedule.toEntity(now: Instant): ScheduleEntity = ScheduleEntity(
    id = id,
    title = title.trim(),
    startAt = startAt?.toEpochMilli(),
    endAt = endAt?.toEpochMilli(),
    isAllDay = isAllDay,
    allDayStartDate = allDayStartDate?.toEpochDay(),
    allDayEndDate = allDayEndDate?.toEpochDay(),
    location = location?.trim()?.ifEmpty { null },
    participants = participants?.trim()?.ifEmpty { null },
    note = note?.trim()?.ifEmpty { null },
    color = color,
    reminderMinutes = reminderMinutes,
    repeatRule = repeatRule,
    repeatInterval = repeatInterval,
    repeatDaysOfWeek = repeatDaysOfWeek.map(DayOfWeek::getValue).sorted().joinToString(",").ifEmpty { null },
    repeatEndDate = repeatEndDate?.toEpochDay(),
    timeZone = timeZone,
    createdAt = if (id == 0L) now.toEpochMilli() else createdAt.toEpochMilli(),
    updatedAt = now.toEpochMilli(),
)

private fun ScheduleExceptionEntity.toDomain(): ScheduleException = ScheduleException(
    scheduleId = scheduleId,
    occurrenceDate = LocalDate.ofEpochDay(occurrenceDate),
    isCancelled = isCancelled,
)

private fun ScheduleException.toEntity(): ScheduleExceptionEntity = ScheduleExceptionEntity(
    scheduleId = scheduleId,
    occurrenceDate = occurrenceDate.toEpochDay(),
    isCancelled = isCancelled,
)
