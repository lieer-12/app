package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SubscriptionNavigationRequest(val token: Long, val subscriptionId: Long)

/** Activity-scoped, consumable events: repeated taps work without replay on rotation. */
class SubscriptionNavigationViewModel : ViewModel() {
    private var nextToken = 0L
    private val requests = MutableStateFlow<SubscriptionNavigationRequest?>(null)
    val pending = requests.asStateFlow()

    fun open(subscriptionId: Long) {
        if (subscriptionId > 0) requests.value = SubscriptionNavigationRequest(++nextToken, subscriptionId)
    }
    fun consume(token: Long) {
        requests.update { if (it?.token == token) null else it }
    }
}
