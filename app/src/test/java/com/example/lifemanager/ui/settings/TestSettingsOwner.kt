package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.ui.common.GenerationAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.robolectric.RuntimeEnvironment

/** Real, isolated storage for settings used by navigation/Compose regression tests. */
class TestSettingsOwner {
    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
        .addCallback(LifeManagerDatabase.INITIALIZE).build()
    private val generations = DataGenerationRepositoryImpl(database)
    private val coordinator = MaintenanceCoordinator(generations)
    val model = SettingsViewModel(SettingsRepositoryImpl(database), Dispatchers.IO,
        GenerationAccess(generations, coordinator))
    fun close() { model.viewModelScope.cancel(); database.close() }
}
