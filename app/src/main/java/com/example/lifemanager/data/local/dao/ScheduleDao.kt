package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY isAllDay DESC, allDayStartDate ASC, startAt ASC, createdAt DESC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY isAllDay DESC, allDayStartDate ASC, startAt ASC, createdAt DESC")
    suspend fun getAll(): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: ScheduleExceptionEntity)

    @Query("SELECT * FROM schedule_exceptions WHERE scheduleId = :scheduleId")
    suspend fun getExceptions(scheduleId: Long): List<ScheduleExceptionEntity>

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
