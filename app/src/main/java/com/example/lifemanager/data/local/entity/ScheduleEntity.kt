package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lifemanager.domain.model.ScheduleRepeatRule

@Entity(
    tableName = "schedules",
    indices = [Index("startAt"), Index("allDayStartDate")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startAt: Long?,
    val endAt: Long?,
    val isAllDay: Boolean,
    val allDayStartDate: Long?,
    val allDayEndDate: Long?,
    val location: String?,
    val participants: String?,
    val note: String?,
    val color: Int,
    val reminderMinutes: Int?,
    val repeatRule: ScheduleRepeatRule,
    val repeatInterval: Int,
    val repeatDaysOfWeek: String?,
    val repeatEndDate: Long?,
    val timeZone: String,
    val createdAt: Long,
    val updatedAt: Long,
)
