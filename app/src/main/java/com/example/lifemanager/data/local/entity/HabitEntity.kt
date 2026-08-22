package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.lifemanager.domain.model.HabitFrequencyType

@Entity(tableName = "habits")
data class HabitEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val iconKey: String, val color: Int, val frequencyType: HabitFrequencyType, val frequencyValue: Int, val customDaysOfWeek: String?, val startDate: Long, val note: String?, val createdAt: Long, val updatedAt: Long)
