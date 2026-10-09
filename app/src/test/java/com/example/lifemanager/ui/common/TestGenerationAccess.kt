package com.example.lifemanager.ui.common

import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test-only seeded storage; production always uses the persisted Room generation. */
class TestGenerations(initial: Long = 0) : DataGenerationRepository {
    private val value = MutableStateFlow(DataGeneration(initial))
    private val lock = Mutex()
    var currentFailure: Exception? = null
    val nextCurrentFailures = ArrayDeque<Exception>()
    override fun observe() = value
    override suspend fun current(): DataGeneration {
        if (nextCurrentFailures.isNotEmpty()) throw nextCurrentFailures.removeFirst()
        currentFailure?.let { throw it }
        return value.value
    }
    override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration = lock.withLock {
        if (value.value != expected) throw StaleGenerationException()
        mutation()
        DataGeneration(Math.addExact(expected.value, 1)).also { value.value = it }
    }
}

fun testGenerationAccess(): GenerationAccess {
    val generations = TestGenerations()
    return GenerationAccess(generations, MaintenanceCoordinator(generations))
}
