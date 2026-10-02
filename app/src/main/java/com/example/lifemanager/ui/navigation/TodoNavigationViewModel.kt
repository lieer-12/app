package com.example.lifemanager.ui.navigation

import androidx.lifecycle.ViewModel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TodoNavigationRequest(val token: Long, val todoId: Long)

/** Activity-scoped notification events survive rotation without replaying consumed Intents. */
class TodoNavigationViewModel : ViewModel() {
    private val nextToken = AtomicLong()
    private val requests = MutableStateFlow<TodoNavigationRequest?>(null)
    val pending = requests.asStateFlow()
    fun open(todoId: Long) {
        if (todoId > 0) requests.value = TodoNavigationRequest(nextToken.incrementAndGet(), todoId)
    }
    fun consume(token: Long) { requests.update { if (it?.token == token) null else it } }
}
