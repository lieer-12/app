package com.example.lifemanager.data.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.domain.backup.*
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Real ContentResolver/Downloads provider, synthetic data, no file-picker or restore claim. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupFilesDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun roomSnapshotExportsReopensAndPreviewsRealDocumentWithoutChangingCurrentData(): Unit = runBlocking {
        val database = newDatabase()
        val uri = newOwnedDocument()
        try {
            val sqlite = database.openHelper.writableDatabase
            fixtures.forEach(sqlite::execSQL)
            sqlite.execSQL("UPDATE app_maintenance SET generation=73 WHERE id=1")
            SettingsRepositoryImpl(database).updateSettings {
                AppSettings(ThemeMode.DARK, DateFormat.DMY, "EUR", false, true, false, setOf(1, 3, 7))
            }
            val repository = BackupRepositoryImpl(database)
            val generations = DataGenerationRepositoryImpl(database)
            val codec = BackupJsonCodec()
            val store = AndroidBackupFileStore(context, Dispatchers.IO)
            val useCases = BackupFileUseCases(repository, codec, store, MaintenanceCoordinator(generations), Dispatchers.IO)
            val before = repository.snapshot()
            val temporaryBefore = stagedNames()
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ByteArray(32_768) { 65 }) }
            val location = BackupLocation(uri.toString())
            val exported = useCases.export(location, "0.1.0", "2026-10-04T00:00:00Z")
            assertEquals(before, exported.payload)
            assertEquals(75, exported.payload.tables.values.sumOf { it.single().fields.size })
            assertEquals(exported, codec.decode(store.read(location, BackupLimits().maxBytes)))
            val preview = useCases.preview(location)
            assertEquals(exported, preview.document)
            assertEquals(10, preview.totalRows)
            assertEquals(before, repository.snapshot())
            assertEquals(73L, generations.current().value)
            assertEquals(temporaryBefore, stagedNames())
            assertNotNull(context.contentResolver.openInputStream(uri)?.use { it.read() })
        } finally {
            database.close()
            // Exact URI returned by this test's insert, never a directory or pre-existing file.
            assertEquals(1, context.contentResolver.delete(uri, null, null))
        }
    }

    @Test fun corruptOrDeletedInputDoesNotChangeDatabaseAndLeavesNoTemporaryFile(): Unit = runBlocking {
        val database = newDatabase()
        val uri = newOwnedDocument()
        var deleted = false
        try {
            database.openHelper.writableDatabase.execSQL("INSERT INTO todos VALUES (41, '合成测试', NULL, 'HIGH', NULL, 0, NULL, 7, 8, NULL, -3)")
            val repository = BackupRepositoryImpl(database)
            val generations = DataGenerationRepositoryImpl(database)
            val store = AndroidBackupFileStore(context, Dispatchers.IO)
            val useCases = BackupFileUseCases(repository, BackupJsonCodec(), store, MaintenanceCoordinator(generations), Dispatchers.IO)
            val before = repository.snapshot()
            val generation = generations.current()
            val temporaryBefore = stagedNames()
            val location = BackupLocation(uri.toString())
            store.write(location, "{\"format\":\"broken\"}".toByteArray())
            assertFailsWith<BackupValidationException> { useCases.preview(location) }
            assertEquals(before, repository.snapshot())
            assertEquals(temporaryBefore, stagedNames())
            assertEquals(1, context.contentResolver.delete(uri, null, null))
            deleted = true
            assertFailsWith<BackupFileException> { useCases.preview(location) }
            assertEquals(before, repository.snapshot())
            assertEquals(generation, generations.current())
            assertEquals(temporaryBefore, stagedNames())
        } finally {
            database.close()
            if (!deleted) assertEquals(1, context.contentResolver.delete(uri, null, null))
        }
    }

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java)
        .addCallback(LifeManagerDatabase.INITIALIZE).build()

    private fun newOwnedDocument(): Uri = checkNotNull(context.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "phase5-synthetic-${UUID.randomUUID()}.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Phase5-tests")
            // Keep synthetic content unpublished; only the creating app needs to read it.
            put(MediaStore.Downloads.IS_PENDING, 1)
        },
    ))

    private fun stagedNames() = File(context.cacheDir, "backup-inputs").listFiles()?.map { it.name }?.toSet().orEmpty()

    private val fixtures = listOf(
        "INSERT INTO todos VALUES (41, '  合成待办🏠  ', NULL, 'HIGH', -1000, 0, NULL, 7, 8, NULL, -3)",
        "INSERT INTO tags VALUES (4, '合成标签', -123, 7)",
        "INSERT INTO todo_tag_cross_ref VALUES (41, 4)",
        "INSERT INTO schedules VALUES (6, '合成日程', 1000, 2000, 0, NULL, NULL, '', '甲,乙', '备注', -123, 15, 'NONE', 1, '7,1,1', -1, 'UTC', 7, 8)",
        "INSERT INTO schedule_exceptions VALUES (6, 21000, 0)",
        "INSERT INTO habits VALUES (5, '合成习惯', 'book', -123, 'CUSTOM', 1, '7,1,1', 20000, NULL, 7, 8)",
        "INSERT INTO habit_records VALUES (9, 5, 19999, 7)",
        "INSERT INTO subscriptions VALUES (8, '合成订阅', 9223372036854775807, 'CNY', 'MONTHLY', 20000, 19900, NULL, '', 0, 20001, 7, 8)",
        "INSERT INTO subscription_payments VALUES (3, 8, 9007199254740993, 'EUR', 19999, '合成历史')",
        "INSERT INTO subscription_reminders VALUES (8, 3)",
    )
}
