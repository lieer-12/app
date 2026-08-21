package com.example.lifemanager.data.local

import androidx.room.TypeConverter
import com.example.lifemanager.domain.model.TodoPriority
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
}
