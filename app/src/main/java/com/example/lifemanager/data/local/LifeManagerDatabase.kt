package com.example.lifemanager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.example.lifemanager.data.local.dao.TagDao
import com.example.lifemanager.data.local.dao.TodoDao
import com.example.lifemanager.data.local.dao.TodoTagDao
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef

@Database(
    entities = [TodoEntity::class, TagEntity::class, TodoTagCrossRef::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LifeManagerDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun tagDao(): TagDao
    abstract fun todoTagDao(): TodoTagDao

    companion object {
        // Future schema changes must be appended here and registered by the builder.
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
