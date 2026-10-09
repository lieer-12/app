package com.example.lifemanager.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.DataGenerationRepository
import com.example.lifemanager.domain.maintenance.StaleGenerationException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataGenerationRepositoryImpl @Inject constructor(private val database: LifeManagerDatabase) : DataGenerationRepository {
    override fun observe(): Flow<DataGeneration> = database.settingsDao().observeGeneration()
        .map { current() }.distinctUntilChanged()

    override suspend fun current(): DataGeneration = database.withTransaction { readGeneration() }

    override suspend fun commit(expected: DataGeneration, mutation: suspend () -> Unit): DataGeneration =
        database.withTransaction {
            if (readGeneration() != expected) throw StaleGenerationException()
            check(expected.value < Long.MAX_VALUE) { "数据世代已达上限，不能安全更换数据" }
            val next = DataGeneration(expected.value + 1)
            mutation()
            // Backup payloads must never write/reset local metadata, even accidentally.
            check(readGeneration() == expected) { "维护操作不应修改本机世代" }
            check(database.settingsDao().advanceGeneration(expected.value, next.value) == 1) {
                "推进数据世代失败"
            }
            next
        }

    private fun readGeneration(): DataGeneration = database.backupSnapshotDao()
        .read(SimpleSQLiteQuery("SELECT generation FROM app_maintenance WHERE id=1"))
        .use { cursor ->
            check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "本机世代记录缺失或损坏" }
            val value = cursor.getLong(0)
            check(value >= 0 && !cursor.moveToNext()) { "本机世代记录无效" }
            DataGeneration(value)
        }
}
