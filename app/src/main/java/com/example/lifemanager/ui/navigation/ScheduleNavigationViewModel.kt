package com.example.lifemanager.ui.navigation

import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

data class ScheduleNavigationRequest(
    override val token: Long,
    val scheduleId: Long,
    override val originalDataGeneration: DataGeneration,
) : GenerationNavigationRequest {
    override val entityId get() = scheduleId
}

/** Retained notification events; only the matching acknowledgement can consume a request. */
@HiltViewModel
class ScheduleNavigationViewModel @Inject constructor(
    access: GenerationAccess,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : GenerationNavigationViewModel<ScheduleNavigationRequest>(access, dispatcher, ::ScheduleNavigationRequest)
