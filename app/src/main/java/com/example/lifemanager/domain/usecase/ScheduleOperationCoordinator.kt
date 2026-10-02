package com.example.lifemanager.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes schedule writes and reminder side effects within the app process. */
object ScheduleOperationCoordinator {
    private val mutex = Mutex()
    suspend fun <T> run(action: suspend () -> T): T = mutex.withLock { action() }
}
