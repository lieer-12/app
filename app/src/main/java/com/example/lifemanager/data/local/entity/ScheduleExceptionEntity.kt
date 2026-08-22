package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "schedule_exceptions",
    primaryKeys = ["scheduleId", "occurrenceDate"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scheduleId")],
)
data class ScheduleExceptionEntity(
    val scheduleId: Long,
    val occurrenceDate: Long,
    val isCancelled: Boolean,
)
