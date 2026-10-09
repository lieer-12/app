package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room

object LifeManagerDatabaseFactory {
    fun open(context: Context, name: String = "life-manager.db"): LifeManagerDatabase =
        Room.databaseBuilder(context, LifeManagerDatabase::class.java, name)
            .addMigrations(*LifeManagerDatabase.MIGRATIONS)
            .addCallback(LifeManagerDatabase.INITIALIZE)
            .build()
}
