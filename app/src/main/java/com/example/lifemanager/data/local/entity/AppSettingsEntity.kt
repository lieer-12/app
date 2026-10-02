package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val theme: String = "SYSTEM",
    val dateFormat: String = "YMD",
    val defaultCurrency: String = "CNY",
    val todoReminders: Boolean = true,
    val scheduleReminders: Boolean = true,
    val subscriptionReminders: Boolean = true,
    val defaultReminderMask: Int = 0,
)
