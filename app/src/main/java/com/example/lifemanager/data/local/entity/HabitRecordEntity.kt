package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "habit_records", foreignKeys = [ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE)], indices = [Index("habitId"), Index(value = ["habitId", "date"], unique = true)])
data class HabitRecordEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val habitId: Long, val date: Long, val createdAt: Long)
