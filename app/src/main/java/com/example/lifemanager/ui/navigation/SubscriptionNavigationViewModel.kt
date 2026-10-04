package com.example.lifemanager.ui.navigation

import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.ui.common.GenerationAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

data class SubscriptionNavigationRequest(
    override val token: Long,
    val subscriptionId: Long,
    override val originalDataGeneration: DataGeneration,
) : GenerationNavigationRequest {
    override val entityId get() = subscriptionId
}

/** Activity-scoped, consumable events: repeated taps work without replay on rotation. */
@HiltViewModel
class SubscriptionNavigationViewModel @Inject constructor(
    access: GenerationAccess,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : GenerationNavigationViewModel<SubscriptionNavigationRequest>(access, dispatcher, ::SubscriptionNavigationRequest)
