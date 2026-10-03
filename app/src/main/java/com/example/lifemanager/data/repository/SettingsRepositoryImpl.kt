package com.example.lifemanager.data.repository

import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.AppSettingsEntity
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.usecase.SettingsRules
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl @Inject constructor(private val database: LifeManagerDatabase) : SettingsRepository {
    private val dao = database.settingsDao()

    override fun observeSettings(): Flow<AppSettings> = flow {
        database.withTransaction { initialize() }
        emitAll(dao.observe().map { checkNotNull(it) { "设置记录不可用" }.toDomain() })
    }

    override suspend fun getSettings(): AppSettings = database.withTransaction {
        initialize()
        checkNotNull(dao.get()).toDomain()
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        database.withTransaction {
            initialize()
            val current = checkNotNull(dao.get()).toDomain()
            val changed = transform(current)
            require(SettingsRules.validate(changed) == null) { SettingsRules.validate(changed).orEmpty() }
            dao.save(changed.toEntity())
        }
    }

    private suspend fun initialize() {
        dao.initialize(AppSettingsEntity())
    }
}

internal fun AppSettingsEntity.toDomain(): AppSettings {
    require(id == 1 && defaultReminderMask in 0..7) { "设置记录格式无效" }
    return AppSettings(
    theme = ThemeMode.valueOf(theme), dateFormat = DateFormat.valueOf(dateFormat),
    defaultCurrency = defaultCurrency, todoReminders = todoReminders,
    scheduleReminders = scheduleReminders, subscriptionReminders = subscriptionReminders,
    defaultReminderDays = listOf(1 to 1, 3 to 2, 7 to 4).filter { defaultReminderMask and it.second != 0 }.map { it.first }.toSet(),
    ).also { require(SettingsRules.validate(it) == null) { "已保存的设置无效" } }
}

internal fun AppSettings.toEntity() = AppSettingsEntity(
    theme = theme.name, dateFormat = dateFormat.name, defaultCurrency = defaultCurrency,
    todoReminders = todoReminders, scheduleReminders = scheduleReminders, subscriptionReminders = subscriptionReminders,
    defaultReminderMask = defaultReminderDays.sumOf { when (it) { 1 -> 1; 3 -> 2; 7 -> 4; else -> error("不支持的提醒天数") } },
)
