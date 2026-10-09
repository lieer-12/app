package com.example.lifemanager.ui.settings

import com.example.lifemanager.domain.model.AppSettings
import com.example.lifemanager.domain.maintenance.DataGeneration

data class SettingsUiState(
    val settings: AppSettings? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isAvailable: Boolean = false,
    val errorMessage: String? = null,
    val generation: DataGeneration? = null,
    val isMaintaining: Boolean = false,
)
