package com.example.lifemanager.data.repository

import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.HabitEntity
import com.example.lifemanager.data.local.entity.HabitRecordEntity
import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitRecord
import com.example.lifemanager.domain.repository.HabitRepository
import com.example.lifemanager.domain.usecase.HabitRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HabitRepositoryImpl @Inject constructor(private val database: LifeManagerDatabase) : HabitRepository {
    private val dao = database.habitDao()
    override fun observeHabits(): Flow<List<Habit>> = dao.observeAll().map { it.map(HabitEntity::toDomain) }
    override fun observeRecords(start: LocalDate, end: LocalDate): Flow<List<HabitRecord>> = dao.recordsBetween(start.toEpochDay(), end.toEpochDay()).map { it.map(HabitRecordEntity::toDomain) }
    override suspend fun saveHabit(habit: Habit): Long { val now = Instant.now(); val inserted = dao.upsert(habit.toEntity(now)); return if (habit.id == 0L) inserted else habit.id }
    override suspend fun deleteHabit(id: Long) = dao.deleteHabit(id)
    override suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean = database.withTransaction {
        val epochDay = date.toEpochDay()
        // Legacy records remain removable even after the start date/frequency changes.
        if (dao.recordForDay(habitId, epochDay) == null) {
            val habit = requireNotNull(dao.getById(habitId)) { "习惯不存在或已被删除" }.toDomain()
            require(HabitRules.isExpectedOn(habit, date)) { "此日期早于开始日期或不是指定打卡日" }
        }
        dao.toggleRecord(habitId, epochDay, Instant.now().toEpochMilli())
    }
}
private fun HabitEntity.toDomain() = Habit(id, name, iconKey, color, frequencyType, frequencyValue, customDaysOfWeek.orEmpty().split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet(), LocalDate.ofEpochDay(startDate), note, Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(updatedAt))
private fun HabitRecordEntity.toDomain() = HabitRecord(id, habitId, LocalDate.ofEpochDay(date), Instant.ofEpochMilli(createdAt))
private fun Habit.toEntity(now: Instant) = HabitEntity(id, name.trim(), iconKey, color, frequencyType, frequencyValue, customDaysOfWeek.map(DayOfWeek::getValue).sorted().joinToString(",").ifEmpty { null }, startDate.toEpochDay(), note?.trim()?.ifEmpty { null }, if (id == 0L) now.toEpochMilli() else createdAt.toEpochMilli(), now.toEpochMilli())
