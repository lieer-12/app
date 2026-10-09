package com.example.lifemanager.ui.navigation

import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

data class TodoNavigationRequest(
    override val token: Long,
    val todoId: Long,
    override val originalDataGeneration: DataGeneration,
) : GenerationNavigationRequest {
    override val entityId get() = todoId
}

/** Activity-scoped notification events survive rotation without replaying consumed Intents. */
@HiltViewModel
class TodoNavigationViewModel @Inject constructor(
    access: GenerationAccess,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : GenerationNavigationViewModel<TodoNavigationRequest>(access, dispatcher, ::TodoNavigationRequest)
