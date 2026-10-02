package com.example.lifemanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.usecase.SettingsRules
import java.util.Locale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState())
    val uiState = state.asStateFlow()
    private val reload = MutableStateFlow(0L)

    init {
        viewModelScope.launch(dispatcher) {
            reload.collectLatest {
                state.update { it.copy(isLoading = true, isAvailable = false) }
                try {
                    repository.observeSettings().collect { saved ->
                        state.update { it.copy(settings = saved, isLoading = false, isAvailable = true) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    state.update { it.copy(isLoading = false, isAvailable = false, errorMessage = "读取设置失败，请重试") }
                }
            }
        }
    }

    fun retry() {
        if (state.value.isSaving) return
        state.update { it.copy(isLoading = true, isAvailable = false, errorMessage = null) }
        reload.update { it + 1 }
    }

    fun setTheme(theme: ThemeMode) = saveSetting { it.copy(theme = theme) }
    fun setDateFormat(format: DateFormat) = saveSetting { it.copy(dateFormat = format) }
    fun setDefaultCurrency(currency: String) = saveSetting { it.copy(defaultCurrency = currency.trim().uppercase(Locale.ROOT)) }
    fun setDefaultReminderDay(day: Int, selected: Boolean) = saveSetting {
        require(day in setOf(1, 3, 7)) { "订阅提醒仅支持提前 1、3、7 天" }
        it.copy(defaultReminderDays = if (selected) it.defaultReminderDays + day else it.defaultReminderDays - day)
    }

    private fun saveSetting(transform: (AppSettings) -> AppSettings) {
        val previous = state.getAndUpdate {
            if (!it.isAvailable || it.settings == null || it.isLoading || it.isSaving) it
            else it.copy(isSaving = true, errorMessage = null)
        }
        if (!previous.isAvailable || previous.settings == null || previous.isLoading || previous.isSaving) return
        viewModelScope.launch(dispatcher) {
            try { repository.updateSettings {
                val next = transform(it)
                require(SettingsRules.validate(next) == null) { SettingsRules.validate(next).orEmpty() }
                next
            } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                state.update { it.copy(errorMessage = if (error is IllegalArgumentException) error.message
                    else "保存设置失败，请重新读取后再次选择") }
            } finally { state.update { it.copy(isSaving = false) } }
        }
    }

}
