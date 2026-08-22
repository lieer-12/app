package com.example.lifemanager.domain.repository

import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeSchedules(): Flow<List<Schedule>>
    suspend fun getSchedules(): List<Schedule>
    suspend fun getExceptions(scheduleId: Long): List<ScheduleException>
    suspend fun saveSchedule(schedule: Schedule): Long
    suspend fun saveException(exception: ScheduleException)
    suspend fun deleteSchedule(id: Long)
}
