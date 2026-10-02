package com.example.lifemanager.ui.settings

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.robolectric.RuntimeEnvironment

/** Real, isolated storage for settings used by navigation/Compose regression tests. */
class TestSettingsOwner {
    private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
        .addCallback(LifeManagerDatabase.INITIALIZE).build()
    val model = SettingsViewModel(SettingsRepositoryImpl(database), Dispatchers.IO)
    fun close() { model.viewModelScope.cancel(); database.close() }
}
