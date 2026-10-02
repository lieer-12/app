package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScheduleNavigationRequest(val token: Long, val scheduleId: Long)

/** Retained notification events; only the matching acknowledgement can consume a request. */
class ScheduleNavigationViewModel : ViewModel() {
    private val nextToken = AtomicLong()
    private val requests = MutableStateFlow<ScheduleNavigationRequest?>(null)
    val pending = requests.asStateFlow()

    fun open(scheduleId: Long) {
        if (scheduleId > 0) requests.value = ScheduleNavigationRequest(nextToken.incrementAndGet(), scheduleId)
    }

    fun consume(token: Long) { requests.update { if (it?.token == token) null else it } }
}
