package com.example.lifemanager.ui.settings

import com.example.lifemanager.domain.model.AppSettings

data class SettingsUiState(
    val settings: AppSettings? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isAvailable: Boolean = false,
    val errorMessage: String? = null,
)
