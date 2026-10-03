package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.lifemanager.data.local.entity.AppSettingsEntity
import com.example.lifemanager.data.local.entity.MaintenanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id=1")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id=1")
    suspend fun get(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initialize(settings: AppSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: AppSettingsEntity)

    @Query("SELECT generation FROM app_maintenance WHERE id=1")
    fun observeGeneration(): Flow<Long?>

    @Query("SELECT generation FROM app_maintenance WHERE id=1")
    suspend fun generation(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initializeMaintenance(metadata: MaintenanceEntity)

    @Query("UPDATE app_maintenance SET generation=:next WHERE id=1 AND generation=:expected")
    suspend fun advanceGeneration(expected: Long, next: Long): Int
}
