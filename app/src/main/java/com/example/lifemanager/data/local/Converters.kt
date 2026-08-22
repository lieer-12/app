package com.example.lifemanager.data.local

import androidx.room.TypeConverter
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.model.ScheduleRepeatRule
import java.time.Instant

class Converters {
    @TypeConverter
    fun fromEpochMillis(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun toEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun fromPriority(value: TodoPriority): String = value.name

    @TypeConverter
    fun toPriority(value: String): TodoPriority = TodoPriority.valueOf(value)

    @TypeConverter
    fun fromScheduleRepeatRule(value: ScheduleRepeatRule): String = value.name

    @TypeConverter
    fun toScheduleRepeatRule(value: String): ScheduleRepeatRule = ScheduleRepeatRule.valueOf(value)
}
