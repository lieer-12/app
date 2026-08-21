package com.example.lifemanager.di

import android.content.Context
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LifeManagerDatabase =
        Room.databaseBuilder(context, LifeManagerDatabase::class.java, "life-manager.db")
            .addMigrations(*LifeManagerDatabase.MIGRATIONS)
            .build()
}
