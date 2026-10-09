package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.AppSettings
import java.util.Currency

object SettingsRules {
    fun validate(settings: AppSettings): String? = when {
        !validCurrency(settings.defaultCurrency) -> "请输入有效的 ISO 4217 币种代码"
        settings.defaultReminderDays.any { it !in setOf(1, 3, 7) } -> "订阅提醒仅支持提前 1、3、7 天"
        else -> null
    }

    private fun validCurrency(code: String): Boolean = try {
        Currency.getInstance(code).defaultFractionDigits >= 0
    } catch (_: IllegalArgumentException) { false }
}
