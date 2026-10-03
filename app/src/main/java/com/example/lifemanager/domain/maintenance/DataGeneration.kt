package com.example.lifemanager.domain.maintenance

import kotlinx.coroutines.flow.Flow

@JvmInline
value class DataGeneration(val value: Long) {
    init { require(value >= 0) { "数据世代无效" } }
}

interface DataGenerationRepository {
    fun observe(): Flow<DataGeneration>
    suspend fun current(): DataGeneration

    /** Only database mutations belong here; mutation and generation advance must be atomic. */
    suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration
}

class MaintenanceBusyException : IllegalStateException("正在维护数据，请稍后重试")
class StaleGenerationException : IllegalStateException("数据已更换，请重新打开并编辑")

enum class MaintenanceState { IDLE, DRAINING, READY, RUNNING }

class MaintenanceSession internal constructor(val generation: DataGeneration)
