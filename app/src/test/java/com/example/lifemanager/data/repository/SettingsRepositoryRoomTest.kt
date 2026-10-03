package com.example.lifemanager.data.repository

import android.app.Application
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.domain.model.DateFormat
import com.example.lifemanager.domain.model.ThemeMode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SettingsRepositoryRoomTest {
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: SettingsRepositoryImpl
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).build()
        repository = SettingsRepositoryImpl(database)
    }
    @After fun cleanup() = database.close()

    @Test fun databaseCreationPersistsDefaultsAndFirstReadDoesNotWriteBusinessRecords(): Unit = runBlocking {
        assertEquals(ThemeMode.SYSTEM, repository.observeSettings().first().theme)
        assertEquals("CNY", repository.getSettings().defaultCurrency)
        assertEquals("SYSTEM", database.settingsDao().get()!!.theme)
        assertTrue(database.todoDao().getAll().isEmpty())
    }

    @Test fun observingSettingsIsSideEffectFreeEvenWithAnInsertRejectingTrigger(): Unit = runBlocking {
        database.settingsDao().get() // Opens the real factory-initialized schema before installing the test trigger.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER test_no_observation_insert BEFORE INSERT ON app_settings BEGIN SELECT RAISE(ABORT, 'observation must not initialize'); END",
        )
        assertEquals(ThemeMode.SYSTEM, repository.observeSettings().first().theme)
    }

    @Test fun observingMissingSettingsFailsInsteadOfRepairingDuringSubscription(): Unit = runBlocking {
        database.openHelper.writableDatabase.execSQL("DELETE FROM app_settings")
        assertFailsWith<IllegalStateException> { repository.observeSettings().first() }
        assertEquals(null, database.settingsDao().get())
    }

    @Test fun settingsRoundTripRetainsIndependentFieldsAndAllReminderBits(): Unit = runBlocking {
        repository.updateSettings { it.copy(theme = ThemeMode.DARK, dateFormat = DateFormat.DMY,
            defaultCurrency = "USD", todoReminders = false, defaultReminderDays = setOf(1, 3, 7)) }
        val reopenedRepository = SettingsRepositoryImpl(database)
        val saved = reopenedRepository.observeSettings().first()
        assertEquals(ThemeMode.DARK, saved.theme)
        assertEquals(DateFormat.DMY, saved.dateFormat)
        assertEquals("USD", saved.defaultCurrency)
        assertEquals(setOf(1, 3, 7), saved.defaultReminderDays)
        assertEquals(false, saved.todoReminders)
        assertEquals(true, saved.scheduleReminders)
        assertEquals(true, saved.subscriptionReminders)
    }

    @Test fun invalidCurrencyDoesNotOverwritePreviouslySavedSettings(): Unit = runBlocking {
        repository.updateSettings { it.copy(theme = ThemeMode.DARK) }
        assertFailsWith<IllegalArgumentException> { repository.updateSettings { it.copy(defaultCurrency = "INVALID") } }
        assertEquals(ThemeMode.DARK, repository.getSettings().theme)
        assertEquals("CNY", repository.getSettings().defaultCurrency)
    }

    @Test fun settingsSurviveClosingAndReopeningAFileBackedDatabase(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "phase5-settings-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, LifeManagerDatabase::class.java, name)
            .addMigrations(*LifeManagerDatabase.MIGRATIONS)
            .addCallback(LifeManagerDatabase.INITIALIZE).build()
        var fileDatabase = open()
        try {
            SettingsRepositoryImpl(fileDatabase).updateSettings { it.copy(theme = ThemeMode.DARK,
                dateFormat = DateFormat.MDY, defaultCurrency = "EUR", defaultReminderDays = setOf(1, 7)) }
            fileDatabase.close()
            fileDatabase = open()
            val saved = SettingsRepositoryImpl(fileDatabase).getSettings()
            assertEquals(ThemeMode.DARK, saved.theme)
            assertEquals(DateFormat.MDY, saved.dateFormat)
            assertEquals("EUR", saved.defaultCurrency)
            assertEquals(setOf(1, 7), saved.defaultReminderDays)
        } finally { fileDatabase.close(); context.deleteDatabase(name) }
    }

    @Test fun unsupportedReminderOffsetDoesNotOverwriteSelection(): Unit = runBlocking {
        repository.updateSettings { it.copy(defaultReminderDays = setOf(3)) }
        assertFailsWith<IllegalArgumentException> { repository.updateSettings { it.copy(defaultReminderDays = setOf(2)) } }
        assertEquals(setOf(3), repository.getSettings().defaultReminderDays)
    }

    @Test fun concurrentIndependentUpdatesDoNotLoseEitherPreference(): Unit = runBlocking {
        val theme = async { repository.updateSettings { it.copy(theme = ThemeMode.DARK) } }
        val currency = async { repository.updateSettings { it.copy(defaultCurrency = "EUR") } }
        theme.await(); currency.await()
        val saved = repository.getSettings()
        assertEquals(ThemeMode.DARK, saved.theme)
        assertEquals("EUR", saved.defaultCurrency)
    }
}
