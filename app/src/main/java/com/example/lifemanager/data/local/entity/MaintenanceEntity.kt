package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_maintenance")
data class MaintenanceEntity(@PrimaryKey val id: Int = 1, val generation: Long = 0)
