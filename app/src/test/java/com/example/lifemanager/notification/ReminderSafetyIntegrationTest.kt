package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.DataGenerationRepositoryImpl
import com.example.lifemanager.data.repository.ScheduleRepositoryImpl
import com.example.lifemanager.data.repository.SettingsRepositoryImpl
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.data.repository.TodoRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceCoordinator
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Schedule
import com.example.lifemanager.domain.model.ScheduleException
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.repository.TodoRepository
import com.example.lifemanager.domain.repository.ScheduleRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowBroadcastPendingResult
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReminderSafetyIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LifeManagerDatabase
    private lateinit var generations: DataGenerationRepositoryImpl
    private lateinit var coordinator: MaintenanceCoordinator
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var todos: TodoRepositoryImpl
    private lateinit var schedules: ScheduleRepositoryImpl
    private lateinit var subscriptions: SubscriptionRepositoryImpl
    private lateinit var todoScheduler: ReminderScheduler
    private lateinit var scheduleScheduler: ScheduleReminderScheduler
    private lateinit var subscriptionScheduler: SubscriptionReminderScheduler
    private lateinit var reconciler: ReminderReconciler
    private var calibrations = 0
    private val now get() = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java)
            .addCallback(LifeManagerDatabase.INITIALIZE).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("UPDATE app_maintenance SET generation=37 WHERE id=1")
        generations = DataGenerationRepositoryImpl(database)
        coordinator = MaintenanceCoordinator(generations)
        settings = SettingsRepositoryImpl(database)
        todos = TodoRepositoryImpl(database)
        schedules = ScheduleRepositoryImpl(database)
        subscriptions = SubscriptionRepositoryImpl(database)
        val state = ReminderSchedulingState(generations, settings)
        todoScheduler = ReminderScheduler(context) { state }
        scheduleScheduler = ScheduleReminderScheduler(context, { state }, { schedules })
        subscriptionScheduler = SubscriptionReminderScheduler(context) { state }
        reconciler = ReminderReconciler(todos, todoScheduler, schedules, scheduleScheduler,
            subscriptions, subscriptionScheduler, coordinator, settings, ReminderNotifications(context))
    }
    @After fun cleanup() { database.close() }

    @Test fun allSchedulersReadRealGenerationAndSettingsAndOffReconciliationClearsEachModule() = runTest {
        val todo = todo(now.plusSeconds(7200))
        val schedule = schedule(now.plusSeconds(7200))
        val subscription = subscription()
        reconciler.reconcile()
        assertEquals(3, alarms.scheduledAlarms.size)
        alarms.scheduledAlarms.forEach {
            assertEquals(DataGeneration(37), ReminderGeneration.read(shadowOf(it.operation).savedIntent))
        }
        NotificationHelper.showTodoReminder(context, todo.id, todo.title, DataGeneration(37))
        NotificationHelper.showScheduleReminder(context, schedule.id, schedule.title, DataGeneration(37))
        SubscriptionNotificationHelper.showReminder(context, subscription, 1, subscription.nextBillingDate, DataGeneration(37))
        settings.updateSettings { it.copy(todoReminders = false, scheduleReminders = false, subscriptionReminders = false) }
        // Direct save paths also respect settings; no global admission is reacquired by schedulers.
        coordinator.run(DataGeneration(37)) {
            todoScheduler.scheduleCurrent(todo.id, todo.title, todo.dueAt!!)
            scheduleScheduler.scheduleCurrent(schedule)
            subscriptionScheduler.scheduleCurrent(subscription, setOf(1))
        }
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(0, notifications.size())
        repeat(2) { reconciler.reconcile() }
        assertTrue(alarms.scheduledAlarms.isEmpty())
        settings.updateSettings { it.copy(todoReminders = true, scheduleReminders = true, subscriptionReminders = true) }
        reconciler.reconcile()
        assertEquals(3, alarms.scheduledAlarms.size)
    }

    @Test fun everyReceiverRejectsOldOrMissingSourceAfterCommitEvenWhenIdsStillExist() = runTest {
        val todo = todo(now.plusSeconds(600))
        val schedule = schedule(now.plusSeconds(600))
        val subscription = subscription()
        coordinator.withSession { session -> coordinator.withMaintenance(session) { generations.commit(session.generation) {} } }
        for (source in listOf(37L, null)) {
            deliver(todoReceiver(), todoIntent(todo, source))
            deliver(scheduleReceiver(), scheduleIntent(schedule, source))
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }),
                Intent().setData(Uri.parse(ReminderKey.subscriptionData(subscription.id, 1, subscription.nextBillingDate)))
                    .putExtra(SubscriptionReminderReceiver.EXTRA_SUBSCRIPTION_ID, subscription.id)
                    .putExtra(SubscriptionReminderReceiver.EXTRA_DAYS_BEFORE, 1)
                    .putExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE, subscription.nextBillingDate.toString())
                    .apply { source?.let { putExtra(ReminderGeneration.EXTRA, it) } })
        }
        assertEquals(0, notifications.size())
        assertEquals(3, calibrations, "Only missing-source arrivals request fresh calibration")
        deliver(todoReceiver(), todoIntent(todo, 38L))
        deliver(scheduleReceiver(), scheduleIntent(schedule, 38L))
        assertEquals(2, notifications.size(), "Current source and current Room qualifications still deliver")
    }

    @Test fun scheduleDeliveryUsesRoomTitleAndRejectsCancelledDisabledDeletedAndEarlyOccurrences() = runTest {
        val current = schedule(now.plusSeconds(600))
        val intent = scheduleIntent(current, 37L).putExtra(ScheduleReminderReceiver.EXTRA_TITLE, "forged")
        deliver(scheduleReceiver(), intent)
        assertEquals("Room schedule", notifications.allNotifications.single().extras.getCharSequence("android.text"))
        context.getSystemService(NotificationManager::class.java).cancelAll()
        schedules.saveException(ScheduleException(current.id, current.startAt!!.atOffset(ZoneOffset.UTC).toLocalDate(), true))
        deliver(scheduleReceiver(), intent)
        assertEquals(0, notifications.size())
        schedules.saveException(ScheduleException(current.id, current.startAt!!.atOffset(ZoneOffset.UTC).toLocalDate(), false))
        settings.updateSettings { it.copy(scheduleReminders = false) }
        deliver(scheduleReceiver(), intent)
        assertEquals(0, notifications.size())
        settings.updateSettings { it.copy(scheduleReminders = true) }
        schedules.saveSchedule(current.copy(reminderMinutes = 1))
        deliver(scheduleReceiver(), intent)
        assertEquals(0, notifications.size())
        schedules.deleteSchedule(current.id)
        deliver(scheduleReceiver(), intent)
        assertEquals(0, notifications.size())
    }

    @Test fun toggleOffDuringEntityReadIsRecheckedBeforePost() = runTest {
        val current = todo(now.plusSeconds(600))
        val observed = object : TodoRepository by todos {
            override suspend fun getAllTodos(): List<Todo> {
                val rows = todos.getAllTodos()
                settings.updateSettings { it.copy(todoReminders = false) }
                return rows
            }
        }
        deliver(todoReceiver(observed), todoIntent(current, 37L))
        assertEquals(0, notifications.size())
    }

    @Test fun lostPostCommitCleanupIsRecoveredFromGenerationAndOperationalInventory() = runTest {
        val current = todo(now.plusSeconds(7200))
        reconciler.reconcile()
        val oldAlarm = Intent(shadowOf(alarms.scheduledAlarms.single().operation).savedIntent)
        NotificationHelper.showTodoReminder(context, current.id, "old", DataGeneration(37))
        coordinator.withSession { session -> coordinator.withMaintenance(session) {
            generations.commit(session.generation) { todos.deleteTodo(current.id) }
        } }
        // Equivalent to losing in-memory postcommit cleanup: a fresh pass has no old business rows.
        reconciler.reconcile()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(0, notifications.size())
        deliver(todoReceiver(), oldAlarm)
        assertEquals(0, notifications.size())
    }

    @Test fun unreadableSettingsFailClosedWithoutDefaultingOrPosting() = runTest {
        val current = todo(now.plusSeconds(600))
        database.openHelper.writableDatabase.execSQL("DELETE FROM app_settings")
        deliver(todoReceiver(), todoIntent(current, 37L))
        assertEquals(0, notifications.size())
        assertFailsWith<IllegalStateException> { reconciler.reconcile() }
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun consumedSubscriptionAlarmDuringCancelledFreezeIsRearmedNotMistakenForPendingIntent() = runTest {
        var clock = Instant.parse("2026-10-05T08:00:00Z")
        val zone = java.time.ZoneId.of("UTC")
        subscriptionScheduler.nowProvider = { clock }
        subscriptionScheduler.zoneProvider = { zone }
        val value = Subscription(appName = "synthetic blocked alarm", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.of(2026, 10, 6), startDate = LocalDate.of(2026, 9, 6), createdAt = clock, updatedAt = clock)
        val current = value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
        subscriptionScheduler.scheduleCurrent(current, setOf(1))
        val old = alarms.scheduledAlarms.single().operation!!
        val payload = Intent(shadowOf(old).savedIntent)
        clock = Instant.parse("2026-10-05T09:05:00Z")
        context.getSystemService(AlarmManager::class.java).cancel(old) // OS consumed the alarm, PI still exists.
        coordinator.withSession {
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), payload)
        } // Equivalent to declining the final confirmation: no generation/data change.
        reconciler.reconcile()
        assertEquals(1, alarms.scheduledAlarms.size)
        val rearmed = alarms.scheduledAlarms.single()
        assertEquals(clock.toEpochMilli() + 1_000, rearmed.triggerAtTime)
        assertEquals(DataGeneration(37), ReminderGeneration.read(shadowOf(rearmed.operation!!).savedIntent))
        assertEquals(current.nextBillingDate.toString(), shadowOf(rearmed.operation!!).savedIntent.getStringExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE))
        assertEquals(1, calibrations)
        assertEquals(0, notifications.size())
    }

    @Test fun scheduleDueDuringCancelledFreezeAndDelayedInexactDeliveryRemainRecoverable() = runTest {
        var clock = Instant.parse("2026-10-05T08:00:00Z")
        val state = ReminderSchedulingState(generations, settings)
        scheduleScheduler = ScheduleReminderScheduler(context, { state }, { schedules }, { clock })
        reconciler = ReminderReconciler(todos, todoScheduler, schedules, scheduleScheduler, subscriptions,
            subscriptionScheduler, coordinator, settings, ReminderNotifications(context))
        val current = schedule(Instant.parse("2026-10-05T09:10:00Z"))
        scheduleScheduler.scheduleCurrent(current)
        val old = alarms.scheduledAlarms.single().operation!!
        val payload = Intent(shadowOf(old).savedIntent)
        clock = Instant.parse("2026-10-05T09:00:00Z")
        context.getSystemService(AlarmManager::class.java).cancel(old)
        coordinator.withSession { deliver(scheduleReceiver(), payload) }
        reconciler.reconcile()
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(clock.toEpochMilli() + 1_000, alarms.scheduledAlarms.single().triggerAtTime)
        // A second fresh calibration must not drop an overdue but still undelivered inexact alarm.
        reconciler.reconcile()
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(current.startAt!!.toEpochMilli(), shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent.getLongExtra(ScheduleReminderReceiver.EXTRA_OCCURRENCE_START, -1))
        assertEquals(0, notifications.size())
    }

    @Test @Config(shadows = [RetryAlarmService::class])
    fun failedSubscriptionRearmRetainsTodayForNextRetry() = runTest {
        var clock = Instant.parse("2026-10-05T08:00:00Z")
        subscriptionScheduler.nowProvider = { clock }
        subscriptionScheduler.zoneProvider = { java.time.ZoneId.of("UTC") }
        val value = Subscription(appName = "synthetic retry", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.of(2026, 10, 6), startDate = LocalDate.of(2026, 9, 6), createdAt = clock, updatedAt = clock)
        val current = value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
        subscriptionScheduler.scheduleCurrent(current, setOf(1))
        val old = alarms.scheduledAlarms.single().operation!!
        context.getSystemService(AlarmManager::class.java).cancel(old)
        subscriptionScheduler.acknowledgeArrival(current.id, 1, current.nextBillingDate, DataGeneration(37))
        clock = Instant.parse("2026-10-05T09:05:00Z")
        RetryAlarmService.failNext = true
        assertFailsWith<IllegalStateException> { subscriptionScheduler.scheduleCurrent(current, setOf(1)) }
        subscriptionScheduler.scheduleCurrent(current, setOf(1))
        val retry = alarms.scheduledAlarms.single()
        assertEquals(clock.toEpochMilli() + 1_000, retry.triggerAtTime)
        assertEquals(current.nextBillingDate.toString(), shadowOf(retry.operation!!).savedIntent.getStringExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE))
    }

    @Test @Config(shadows = [RetryAlarmService::class])
    fun failedScheduleRearmRetainsOverdueOccurrenceForNextRetry() = runTest {
        var clock = Instant.parse("2026-10-05T08:00:00Z")
        val state = ReminderSchedulingState(generations, settings)
        val scheduler = ScheduleReminderScheduler(context, { state }, { schedules }, { clock })
        val current = schedule(Instant.parse("2026-10-05T09:10:00Z"))
        scheduler.scheduleCurrent(current)
        clock = Instant.parse("2026-10-05T09:00:00Z")
        RetryAlarmService.failNext = true
        assertFailsWith<IllegalStateException> { scheduler.scheduleCurrent(current) }
        scheduler.scheduleCurrent(current)
        assertEquals(clock.toEpochMilli() + 1_000, alarms.scheduledAlarms.single().triggerAtTime)
        assertEquals(current.startAt!!.toEpochMilli(), shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent.getLongExtra(ScheduleReminderReceiver.EXTRA_OCCURRENCE_START, -1))
    }

    @Test fun admittedScheduleReadFailureRetainsHintAndRequestsFreshRetry() = runTest {
        val current = schedule(now.plusSeconds(600))
        val hint = ScheduleAlarmOccurrenceStore(context)
        hint.remember(current.id, current.startAt!!.toEpochMilli(), DataGeneration(37))
        var failRead = true
        val repository = object : ScheduleRepository by schedules {
            override suspend fun getSchedules(): List<Schedule> {
                if (failRead) error("synthetic temporary read failure")
                return schedules.getSchedules()
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val receiver = ScheduleReminderReceiver(dispatcher, { ReminderBroadcastRunner(coordinator, dispatcher) },
            { calibrations++ }, { repository }, { settings })
        deliver(receiver, scheduleIntent(current, 37L))
        assertEquals(current.startAt!!.toEpochMilli(), hint.pending(current.id, DataGeneration(37)))
        assertEquals(1, calibrations)
        assertEquals(0, notifications.size())
        failRead = false
        deliver(receiver, scheduleIntent(current, 37L))
        assertEquals(null, hint.pending(current.id, DataGeneration(37)))
        assertEquals(1, notifications.size())
    }

    @Test fun admittedSubscriptionReadFailureRequestsRecoveryOfConsumedTodayAlarm() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val alarm = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(alarm).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(alarm)
            clock = Instant.now()
            var failRead = true
            val repository = object : com.example.lifemanager.domain.repository.SubscriptionRepository by subscriptions {
                override suspend fun getSubscription(id: Long): Subscription? {
                    if (failRead) error("synthetic temporary subscription read failure")
                    return subscriptions.getSubscription(id)
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ repository }, { subscriptionScheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), payload)
            assertEquals(0, notifications.size())
            assertEquals(1, calibrations)
            failRead = false
            reconciler.reconcile()
            assertEquals(clock.toEpochMilli() + 1_000, alarms.scheduledAlarms.single().triggerAtTime)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test fun cancellationDuringSubscriptionRearmKeepsConsumedOccurrenceUntilDelivery() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            var cancelRead = false
            val guardedSettings = object : com.example.lifemanager.domain.repository.SettingsRepository by settings {
                override suspend fun getSettings(): com.example.lifemanager.domain.model.AppSettings {
                    if (cancelRead) throw kotlinx.coroutines.CancellationException("synthetic rearm timeout")
                    return settings.getSettings()
                }
            }
            val state = ReminderSchedulingState(generations, guardedSettings)
            val scheduler = SubscriptionReminderScheduler(context) { state }
            scheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            scheduler.scheduleCurrent(current, setOf(1))
            val alarm = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(alarm).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(alarm)
            clock = Instant.now()
            cancelRead = true
            val dispatcher = StandardTestDispatcher(testScheduler)
            deliver(SubscriptionReminderReceiver({ subscriptions }, { scheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings }), payload)
            assertEquals(0, notifications.size(), "Cancellation before posting must suppress delivery")
            cancelRead = false
            scheduler.scheduleCurrent(current, setOf(1))
            assertEquals(clock.toEpochMilli() + 1_000, alarms.scheduledAlarms.single().triggerAtTime)
            assertEquals(1, calibrations)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test @Config(shadows = [CountingSubscriptionNotifications::class])
    fun replacementArrivalQueuedWhilePostingDoesNotDeliverOccurrenceTwice() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            CountingSubscriptionNotifications.posts = 0
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val old = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(old).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(old)
            clock = Instant.now()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
            var reads = 0
            val observed = object : com.example.lifemanager.domain.repository.SettingsRepository by settings {
                override suspend fun getSettings(): com.example.lifemanager.domain.model.AppSettings {
                    if (++reads == 2) { entered.complete(Unit); resume.await() }
                    return settings.getSettings()
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun receiver() = SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { observed })
            val first = beginDelivery(receiver(), payload)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!entered.isCompleted && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
            assertTrue(entered.isCompleted, "First delivery must pause after rearming and before posting")
            val replacement = alarms.scheduledAlarms.single().operation!!
            context.getSystemService(AlarmManager::class.java).cancel(replacement)
            val second = beginDelivery(receiver(), Intent(shadowOf(replacement).savedIntent))
            runCurrent()
            assertEquals(0, CountingSubscriptionNotifications.posts)
            resume.complete(Unit)
            awaitDelivery(first); awaitDelivery(second)
            assertEquals(1, CountingSubscriptionNotifications.posts, "Already-running replacement cannot post the same occurrence again")
            assertEquals(1, alarms.scheduledAlarms.size)
            assertTrue(alarms.scheduledAlarms.single().triggerAtTime > clock.plusSeconds(86400).toEpochMilli())
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test @Config(shadows = [CountingSubscriptionNotifications::class])
    fun deliveryMarkerNeverSuppressesRestoredSameIdDateOrAcceptsAnOldGenerationCallback() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            CountingSubscriptionNotifications.posts = 0
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val old = Intent(shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent)
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun receiver() = SubscriptionReminderReceiver({ subscriptions }, { subscriptionScheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings })
            clock = Instant.now()
            deliver(receiver(), old)
            assertEquals(1, CountingSubscriptionNotifications.posts)
            coordinator.withSession { session -> coordinator.withMaintenance(session) { generations.commit(session.generation) {} } }
            clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            subscriptionScheduler.scheduleCurrent(current, setOf(1))
            val restored = Intent(shadowOf(alarms.scheduledAlarms.single().operation!!).savedIntent)
            assertEquals(DataGeneration(38), ReminderGeneration.read(restored))
            clock = Instant.now()
            deliver(receiver(), restored)
            assertEquals(2, CountingSubscriptionNotifications.posts, "Old marker must not suppress new generation")
            deliver(receiver(), old)
            deliver(receiver(), restored)
            assertEquals(2, CountingSubscriptionNotifications.posts, "Late old callback must not replace the new delivered marker")
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    @Test @Config(shadows = [CountingSubscriptionNotifications::class])
    fun cancellationAfterRecordingDeliveryStillRebuildsFutureWithoutAnotherPost() = runTest {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(ZoneOffset.ofHours(12 - Instant.now().atOffset(ZoneOffset.UTC).hour)))
        try {
            CountingSubscriptionNotifications.posts = 0
            val zone = java.time.ZoneId.systemDefault()
            var clock = LocalDate.now().atTime(8, 0).atZone(zone).toInstant()
            var cancelFuture = false
            val guarded = object : com.example.lifemanager.domain.repository.SettingsRepository by settings {
                override suspend fun getSettings(): com.example.lifemanager.domain.model.AppSettings {
                    if (cancelFuture && CountingSubscriptionNotifications.posts > 0) throw kotlinx.coroutines.CancellationException("synthetic post-marker interruption")
                    return settings.getSettings()
                }
            }
            val state = ReminderSchedulingState(generations, guarded)
            val scheduler = SubscriptionReminderScheduler(context) { state }
            scheduler.nowProvider = { clock }
            val current = dueSubscription(clock)
            scheduler.scheduleCurrent(current, setOf(1))
            val old = alarms.scheduledAlarms.single().operation!!
            val payload = Intent(shadowOf(old).savedIntent)
            context.getSystemService(AlarmManager::class.java).cancel(old)
            clock = Instant.now(); cancelFuture = true
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun receiver() = SubscriptionReminderReceiver({ subscriptions }, { scheduler }, dispatcher,
                { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings })
            deliver(receiver(), payload)
            assertEquals(1, CountingSubscriptionNotifications.posts)
            assertEquals(1, calibrations)
            cancelFuture = false
            scheduler.scheduleCurrent(current, setOf(1))
            assertTrue(alarms.scheduledAlarms.single().triggerAtTime > clock.plusSeconds(86400).toEpochMilli())
            deliver(receiver(), payload)
            assertEquals(1, CountingSubscriptionNotifications.posts)
        } finally { java.util.TimeZone.setDefault(originalZone) }
    }

    private suspend fun dueSubscription(clock: Instant): Subscription {
        val value = Subscription(appName = "synthetic consumed subscription", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.now().plusDays(1), startDate = LocalDate.now().minusMonths(1), createdAt = clock, updatedAt = clock)
        return value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
    }

    private suspend fun todo(due: Instant): Todo {
        val value = Todo(title = "Room todo", dueAt = due)
        return value.copy(id = todos.saveTodo(value, emptyList()))
    }
    private suspend fun schedule(start: Instant): Schedule {
        val value = Schedule(title = "Room schedule", startAt = start, endAt = start.plusSeconds(3600), reminderMinutes = 15, timeZone = "UTC")
        return value.copy(id = schedules.saveSchedule(value))
    }
    private suspend fun subscription(): Subscription {
        val value = Subscription(appName = "Room subscription", amountMinor = 100, billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.now().plusDays(7), startDate = LocalDate.now(), createdAt = now, updatedAt = now)
        return value.copy(id = subscriptions.saveSubscription(value, setOf(1)))
    }
    private fun TestScope.todoReceiver(repository: TodoRepository = todos): TodoReminderReceiver {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TodoReminderReceiver({ repository }, dispatcher, { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { settings })
    }
    private fun TestScope.scheduleReceiver(): ScheduleReminderReceiver {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ScheduleReminderReceiver(dispatcher, { ReminderBroadcastRunner(coordinator, dispatcher) }, { calibrations++ }, { schedules }, { settings })
    }
    private fun todoIntent(todo: Todo, source: Long?) = Intent().setData(Uri.parse("lifemanager://todo-reminder/${todo.id}"))
        .putExtra(TodoReminderReceiver.EXTRA_TODO_ID, todo.id).putExtra(TodoReminderReceiver.EXTRA_DUE_AT, todo.dueAt!!.toEpochMilli())
        .apply { source?.let { putExtra(ReminderGeneration.EXTRA, it) } }
    private fun scheduleIntent(schedule: Schedule, source: Long?) = Intent().setData(Uri.parse("lifemanager://schedule-reminder/${schedule.id}"))
        .putExtra(ScheduleReminderReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        .putExtra(ScheduleReminderReceiver.EXTRA_OCCURRENCE_START, schedule.startAt!!.toEpochMilli())
        .apply { source?.let { putExtra(ReminderGeneration.EXTRA, it) } }

    private fun TestScope.deliver(receiver: BroadcastReceiver, payload: Intent) {
        awaitDelivery(beginDelivery(receiver, payload))
    }

    private fun TestScope.beginDelivery(receiver: BroadcastReceiver, payload: Intent): ShadowBroadcastPendingResult {
        val intent = Intent(payload).setAction("com.example.lifemanager.TEST_SOURCE")
        context.registerReceiver(receiver, IntentFilter(intent.action).apply { addDataScheme("lifemanager") })
        try {
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
            return shadowOf(assertNotNull(shadowOf(receiver).originalPendingResult))
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun TestScope.awaitDelivery(pending: ShadowBroadcastPendingResult) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!pending.future.isDone && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        assertTrue(pending.future.isDone, "Receiver must finish before closing its isolated Room database")
    }
}

/** Fail at the real scheduler's OS boundary, then use the normal AlarmManager shadow again. */
@Implements(AlarmManager::class)
class RetryAlarmService : ShadowAlarmManager() {
    @Implementation override fun setExactAndAllowWhileIdle(type: Int, at: Long, operation: PendingIntent) {
        if (failNext) { failNext = false; error("synthetic temporary alarm failure") }
        super.setExactAndAllowWhileIdle(type, at, operation)
    }
    companion object { var failNext = false }
}

@Implements(NotificationManager::class)
class CountingSubscriptionNotifications : ShadowNotificationManager() {
    @Implementation override fun notify(tag: String?, id: Int, notification: Notification) {
        if (tag?.startsWith("subscription/") == true) posts++
        super.notify(tag, id, notification)
    }
    companion object { var posts = 0 }
}
