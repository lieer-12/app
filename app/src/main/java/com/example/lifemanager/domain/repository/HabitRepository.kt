package com.example.lifemanager.domain.repository

import com.example.lifemanager.domain.model.Habit
import com.example.lifemanager.domain.model.HabitRecord
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface HabitRepository { fun observeHabits(): Flow<List<Habit>>; fun observeRecords(start: LocalDate, end: LocalDate): Flow<List<HabitRecord>>; suspend fun saveHabit(habit: Habit): Long; suspend fun deleteHabit(id: Long); suspend fun toggleRecord(habitId: Long, date: LocalDate): Boolean }
