package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.lifemanager.data.local.entity.ScheduleEntity
import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY isAllDay DESC, allDayStartDate ASC, startAt ASC, createdAt DESC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY isAllDay DESC, allDayStartDate ASC, startAt ASC, createdAt DESC")
    suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Insert suspend fun insertSchedule(schedule: ScheduleEntity): Long
    @Update suspend fun updateSchedule(schedule: ScheduleEntity): Int

    @Transaction
    suspend fun upsert(schedule: ScheduleEntity): Long {
        if (schedule.id == 0L) return insertSchedule(schedule)
        val original = checkNotNull(getById(schedule.id)) { "日程已删除，请关闭旧表单" }
        check(updateSchedule(schedule.copy(createdAt = original.createdAt)) == 1) { "日程已删除，请关闭旧表单" }
        return schedule.id
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: ScheduleExceptionEntity)

    @Query("SELECT * FROM schedule_exceptions WHERE scheduleId = :scheduleId")
    suspend fun getExceptions(scheduleId: Long): List<ScheduleExceptionEntity>

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
