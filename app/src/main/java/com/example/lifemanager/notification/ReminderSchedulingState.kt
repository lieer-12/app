package com.example.lifemanager.notification

import android.content.Context
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Caller already owns global admission then its module lock. No nested admission here. */
@Singleton
class ReminderSchedulingState @Inject constructor(
    private val generations: DataGenerationRepository,
    private val settings: SettingsRepository,
) {
    suspend fun read(): Pair<DataGeneration, AppSettings> = generations.current() to settings.getSettings()

    companion object {
        fun fromApplication(context: Context): ReminderSchedulingState =
            EntryPointAccessors.fromApplication(context.applicationContext, ReminderSafetyEntryPoint::class.java)
                .reminderSchedulingState()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderSafetyEntryPoint {
    fun reminderSchedulingState(): ReminderSchedulingState
    fun settingsRepository(): SettingsRepository
    fun scheduleRepository(): com.example.lifemanager.domain.repository.ScheduleRepository
}

internal fun reminderSettings(context: Context): SettingsRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, ReminderSafetyEntryPoint::class.java)
        .settingsRepository()
