package com.example.lifemanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.repository.SettingsRepository
import com.example.lifemanager.domain.usecase.SettingsRules
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceBusyException
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import com.example.lifemanager.ui.common.GenerationAccess
import java.util.Locale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val generationAccess: GenerationAccess,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState())
    val uiState = state.asStateFlow()
    private val reload = MutableStateFlow(0L)
    private val generationReload = MutableStateFlow(0L)
    private val observationFailed = MutableStateFlow(false)
    private val generationFailed = MutableStateFlow(false)
    private var activeSave: Any? = null
    // Separate from the settings snapshot's tag: a newer generation may be observed before its prefs load.
    private var observedGeneration: DataGeneration? = null

    init {
        viewModelScope.launch {
            generationAccess.maintenance.collect { maintenance ->
                state.update { it.copy(isMaintaining = maintenance != MaintenanceState.IDLE) }
            }
        }
        viewModelScope.launch(dispatcher) {
            generationReload.collectLatest {
                try {
                    generationAccess.generations.collectLatest { generation ->
                        withContext(Dispatchers.Main.immediate) {
                            observedGeneration = generation
                            observationFailed.value = false
                            activeSave = null
                            state.update { it.copy(isLoading = true, isAvailable = false, isSaving = false, errorMessage = null) }
                        }
                        generationAccess.maintenance.collectLatest { maintenance ->
                            if (maintenance == MaintenanceState.IDLE) {
                                reload.collectLatest {
                                    if (!observationFailed.value) observeSettings(generation)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) { generationReadFailed() }
                }
            }
        }
    }

    private suspend fun observeSettings(generation: DataGeneration) {
        try {
            // The long-lived stream only invalidates. Never tag its buffered value with a later generation.
            repository.observeSettings().collect {
                if (!observationFailed.value) {
                    generationAccess.read({ repository.observeSettings().first() }) { token, saved ->
                        if (!observationFailed.value) publishSettings(token, saved)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is MaintenanceBusyException || error is StaleGenerationException) return
            publishResult(generation) { readFailed() }
        }
    }

    fun retry(generation: DataGeneration? = state.value.generation) {
        if (generationAccess.maintenance.value != MaintenanceState.IDLE || state.value.isSaving ||
            generation != state.value.generation) return
        // A failed initial read has no published snapshot, but must still allow an admitted fresh read.
        if (generation == null || generationFailed.value) {
            requestReload()
            return
        }
        val token = eventToken(generation) ?: return
        viewModelScope.launch(dispatcher) {
            try {
                generationAccess.run(token) {
                    withContext(Dispatchers.Main.immediate) { requestReload() }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is MaintenanceBusyException || error is StaleGenerationException) return@launch
                publishResult(token) { readFailed() }
            }
        }
    }

    private fun requestReload() {
        observationFailed.value = false
        state.update { it.copy(isLoading = true, isAvailable = false, errorMessage = null) }
        if (generationFailed.value) generationReload.update { it + 1 }
        else reload.update { it + 1 }
    }

    fun setTheme(theme: ThemeMode, generation: DataGeneration? = state.value.generation) = saveSetting(generation) { it.copy(theme = theme) }
    fun setDateFormat(format: DateFormat, generation: DataGeneration? = state.value.generation) = saveSetting(generation) { it.copy(dateFormat = format) }
    fun setDefaultCurrency(currency: String, generation: DataGeneration? = state.value.generation) = saveSetting(generation) { it.copy(defaultCurrency = currency.trim().uppercase(Locale.ROOT)) }
    fun setDefaultReminderDay(day: Int, selected: Boolean, generation: DataGeneration? = state.value.generation) = saveSetting(generation) {
        require(day in setOf(1, 3, 7)) { "订阅提醒仅支持提前 1、3、7 天" }
        it.copy(defaultReminderDays = if (selected) it.defaultReminderDays + day else it.defaultReminderDays - day)
    }

    private fun eventToken(generation: DataGeneration?): DataGeneration? = try {
        generationAccess.eventToken(generation)
    } catch (_: IllegalStateException) {
        null
    }

    private fun saveSetting(generation: DataGeneration?, transform: (AppSettings) -> AppSettings) {
        val token = eventToken(generation) ?: return
        val previous = state.getAndUpdate {
            if (!it.isAvailable || it.settings == null || it.isLoading || it.isSaving || it.generation != token) it
            else it.copy(isSaving = true, errorMessage = null)
        }
        if (!previous.isAvailable || previous.settings == null || previous.isLoading || previous.isSaving ||
            previous.generation != token) return
        val save = Any()
        activeSave = save
        viewModelScope.launch(dispatcher) {
            try {
                generationAccess.run(token) {
                    repository.updateSettings {
                        val next = transform(it)
                        require(SettingsRules.validate(next) == null) { SettingsRules.validate(next).orEmpty() }
                        next
                    }
                    // Reuse this outer admission for the fresh observed snapshot and its Main publication.
                    // getSettings() would erase the repository's intentional observation lag.
                    if (!observationFailed.value) {
                        try {
                            val saved = repository.observeSettings().first()
                            withContext(Dispatchers.Main.immediate) {
                                if (!observationFailed.value) publishSettings(token, saved)
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            withContext(Dispatchers.Main.immediate) { readFailed() }
                        }
                    }
                    withContext(Dispatchers.Main.immediate) { finishSave(save) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error !is MaintenanceBusyException && error !is StaleGenerationException) {
                    publishResult(token) {
                        if (!observationFailed.value) {
                            state.update { it.copy(errorMessage = if (error is IllegalArgumentException) error.message
                                else "保存设置失败，请重新读取后再次选择") }
                        }
                    }
                }
            } finally {
                // Cleanup must also run when cancellation prevents admitted result publication.
                withContext(NonCancellable + Dispatchers.Main.immediate) { finishSave(save) }
            }
        }
    }

    private fun publishSettings(generation: DataGeneration, saved: AppSettings) {
        observedGeneration = generation
        generationFailed.value = false
        state.update { it.copy(settings = saved, generation = generation, isLoading = false, isAvailable = true) }
    }

    private suspend fun publishResult(generation: DataGeneration, publish: () -> Unit) {
        try {
            generationAccess.publishResult(generation, publish)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A broken generation provider cannot validate even an error result. Preserve the display,
            // close editing, and require a new admitted read instead of letting the VM coroutine escape.
            withContext(Dispatchers.Main.immediate) {
                if (observedGeneration == generation && (state.value.generation == null || state.value.generation == generation)) generationReadFailed()
            }
        }
    }

    private fun generationReadFailed() {
        generationFailed.value = true
        readFailed()
    }

    private fun readFailed() {
        observationFailed.value = true
        state.update { it.copy(isLoading = false, isAvailable = false, errorMessage = "读取设置失败，请重试") }
    }

    private fun finishSave(save: Any) {
        if (activeSave === save) {
            activeSave = null
            state.update { it.copy(isSaving = false) }
        }
    }

}
