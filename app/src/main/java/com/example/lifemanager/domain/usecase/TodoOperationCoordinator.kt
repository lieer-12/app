package com.example.lifemanager.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep persisted Todo mutations and reminder delivery/reconciliation ordered in this process. */
object TodoOperationCoordinator {
    private val mutex = Mutex()
    suspend fun <T> run(operation: suspend () -> T): T = mutex.withLock { operation() }
}
