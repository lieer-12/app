package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY createdAt DESC") fun observeAll(): Flow<List<HabitEntity>>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun getById(id: Long): HabitEntity?
    @Insert suspend fun insertHabit(habit: HabitEntity): Long
    @Update suspend fun updateHabit(habit: HabitEntity): Int
    @Transaction suspend fun upsert(habit: HabitEntity): Long {
        if (habit.id == 0L) return insertHabit(habit)
        check(updateHabit(habit) == 1) { "习惯已被删除，请关闭旧表单后重试" }
        return habit.id
    }
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteHabit(id: Long)
    @Query("SELECT * FROM habit_records WHERE date BETWEEN :startDate AND :endDate") fun recordsBetween(startDate: Long, endDate: Long): Flow<List<HabitRecordEntity>>
    @Query("SELECT * FROM habit_records WHERE habitId = :habitId AND date = :date LIMIT 1") suspend fun recordForDay(habitId: Long, date: Long): HabitRecordEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRecord(record: HabitRecordEntity): Long
    @Query("DELETE FROM habit_records WHERE id = :id") suspend fun deleteRecord(id: Long)
    @Transaction suspend fun toggleRecord(habitId: Long, date: Long, createdAt: Long): Boolean {
        val existing = recordForDay(habitId, date)
        return if (existing == null) { insertRecord(HabitRecordEntity(habitId = habitId, date = date, createdAt = createdAt)); true } else { deleteRecord(existing.id); false }
    }
}
