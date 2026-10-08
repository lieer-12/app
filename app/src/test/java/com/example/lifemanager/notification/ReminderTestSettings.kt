package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow

internal class ReminderTestSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val values = MutableStateFlow(initial)
    var failure: Exception? = null
    override fun observeSettings() = values
    override suspend fun getSettings(): AppSettings { failure?.let { throw it }; return values.value }
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) { values.value = transform(getSettings()) }
}
