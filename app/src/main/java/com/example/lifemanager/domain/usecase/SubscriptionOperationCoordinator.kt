package com.example.lifemanager.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes subscription mutations and reminder reads/side effects within the app process. */
object SubscriptionOperationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}
