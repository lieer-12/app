package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.repository.SubscriptionRepositoryImpl
import com.example.lifemanager.domain.maintenance.DataGeneration
import com.example.lifemanager.domain.maintenance.MaintenanceState
import com.example.lifemanager.domain.model.BillingCycle
import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.repository.SubscriptionRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.shadows.ShadowBroadcastPendingResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionReminderAdmissionTest {
    private lateinit var database: LifeManagerDatabase
    private lateinit var repository: SubscriptionRepositoryImpl
    private lateinit var admission: ReminderAdmissionFixture
    private lateinit var originalTimeZone: TimeZone
    private var repositoryResolutions = 0
    private val entityReads = mutableListOf<Long>()
    private val reminderReads = mutableListOf<Long>()
    private var schedulerResolutions = 0
    private var alarmOperations = 0
    private var calibrationRequests = 0
    private val context get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before fun setup() {
        originalTimeZone = TimeZone.getDefault()
        // Keep the real Instant clock. A fixed offset puts local now near noon, safely after
        // today's 09:00 trigger and far from midnight, regardless of the host's wall-clock hour.
        val utcHour = Instant.now().atOffset(ZoneOffset.UTC).hour
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofHours(12 - utcHour)))
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = SubscriptionRepositoryImpl(database)
        admission = ReminderAdmissionFixture()
    }

    @After fun cleanup() {
        try { if (::database.isInitialized) database.close() }
        finally { if (::originalTimeZone.isInitialized) TimeZone.setDefault(originalTimeZone) }
    }

    // Removing arrival admission or queuing Busy payloads must expose reads, alarm work or a post.
    @Test fun frozenArrivalFinishesImmediatelyRequestsCalibrationAndNeverReplays(): Unit = runTest {
        val subscription = saveDueSubscription()
        admission.coordinator.withSession {
            assertEquals(MaintenanceState.READY, admission.coordinator.state.value)
            val pending = receive(subscription)
            assertTrue(pending.future.isDone, "Busy arrival must finish before IO dispatch or unfreeze")
            assertEquals(1, calibrationRequests, "Busy must request fresh current-data calibration")
            assertRejected()
            runCurrent()
            assertRejected()
            assertEquals(MaintenanceState.READY, admission.coordinator.state.value)
        }
        runCurrent()
        assertRejected()
        assertEquals(1, calibrationRequests, "Unfreeze must not replay the rejected payload")
        assertEquals(DataGeneration(37), admission.generations.current())
    }

    // Recapturing on IO would read/post this restored row using the old arrival's same ID/date.
    @Test fun queuedArrivalCannotReadOrPostSameIdSubscriptionRestoredBeforeIO(): Unit = runTest {
        val subscription = saveDueSubscription()
        val pending = receive(subscription)
        assertFalse(pending.future.isDone, "Receiver IO must remain queued")
        assertRejected()
        admission.coordinator.withSession { session ->
            admission.coordinator.withMaintenance(session) {
                admission.generations.commit(session.generation) {
                    // Synchronous isolated-Room mutation leaves receiver IO queued until commit.
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE subscriptions SET appName = ? WHERE id = ?",
                        arrayOf<Any>("restored subscription", subscription.id),
                    )
                }
            }
        }
        assertEquals(DataGeneration(38), admission.generations.current())
        assertRejected()
        settleBroadcast(pending)
        assertRejected()
        assertEquals(0, calibrationRequests, "Stale rejection must not request calibration")
        assertEquals("restored subscription", repository.getSubscription(subscription.id)?.appName)
        assertEquals(setOf(1), repository.getReminderDays(subscription.id))
    }

    // Rejecting every arrival, assuming generation 0, or bypassing Room qualification loses this post.
    @Test fun idleLegacyPayloadQualifiesFromRealRoomAtANonzeroGeneration(): Unit = runTest {
        val subscription = saveDueSubscription()
        val pending = receive(subscription)
        settleBroadcast(pending)
        assertEquals(1, repositoryResolutions)
        assertEquals(listOf(subscription.id), entityReads)
        assertEquals(listOf(subscription.id), reminderReads)
        assertEquals(1, schedulerResolutions)
        assertTrue(alarmOperations > 0)
        assertEquals(1, alarms.scheduledAlarms.size, "Valid delivery must rearm its selected offset")
        assertEquals(1, notifications.size(), "Current eligible Room row must still deliver")
        assertTrue(notifications.allNotifications.single().extras.getCharSequence("android.text")
            .toString().contains("current subscription"), "Notification must use the Room name")
        assertEquals(0, calibrationRequests)
        assertEquals(DataGeneration(37), admission.generations.current())
    }

    private suspend fun saveDueSubscription(): Subscription {
        assertTrue(LocalTime.now().isAfter(LocalTime.of(9, 0)), "Fixture must satisfy existing 09:00 qualification")
        val dueDate = LocalDate.now().plusDays(1)
        val subscription = Subscription(
            appName = "current subscription", amountMinor = 1800L, currency = "CNY",
            billingCycle = BillingCycle.MONTHLY, nextBillingDate = dueDate,
            startDate = dueDate.minusMonths(1), category = "fixture", note = "synthetic",
            isActive = true, cancelDate = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        return subscription.copy(id = repository.saveSubscription(subscription, setOf(1)))
    }

    private fun TestScope.receive(subscription: Subscription): ShadowBroadcastPendingResult {
        // Observation delegates both queries to the actual repository; it supplies no fake rows.
        val observedRepository = object : SubscriptionRepository by repository {
            override suspend fun getSubscription(id: Long): Subscription? {
                entityReads.add(id)
                return repository.getSubscription(id)
            }
            override suspend fun getReminderDays(subscriptionId: Long): Set<Int> {
                reminderReads.add(subscriptionId)
                return repository.getReminderDays(subscriptionId)
            }
        }
        // Keep even the Android scheduler real; count entry to catch side effects on rejected paths.
        val realScheduler by lazy { SubscriptionReminderScheduler(context) }
        val observedScheduler = object : SubscriptionReminderSchedulerContract {
            override fun schedule(subscription: Subscription, reminderDays: Set<Int>) {
                alarmOperations++
                realScheduler.schedule(subscription, reminderDays)
            }
            override fun cancel(subscriptionId: Long, reminderDays: Set<Int>) {
                alarmOperations++
                realScheduler.cancel(subscriptionId, reminderDays)
            }
            override fun cancelAll(subscriptionId: Long) {
                alarmOperations++
                realScheduler.cancelAll(subscriptionId)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val receiver = SubscriptionReminderReceiver(
            repositoryProvider = { repositoryResolutions++; observedRepository },
            schedulerProvider = { schedulerResolutions++; observedScheduler },
            dispatcher = dispatcher,
            runnerProvider = { ReminderBroadcastRunner(admission.coordinator, dispatcher) },
            onBusy = { calibrationRequests++ },
        )
        val intent = Intent("com.example.lifemanager.TEST_SUBSCRIPTION_ADMISSION")
            .setData(Uri.parse(ReminderKey.subscriptionData(subscription.id, 1, subscription.nextBillingDate)))
            .putExtra(SubscriptionReminderReceiver.EXTRA_SUBSCRIPTION_ID, subscription.id)
            .putExtra(SubscriptionReminderReceiver.EXTRA_DAYS_BEFORE, 1)
            .putExtra(SubscriptionReminderReceiver.EXTRA_DUE_DATE, subscription.nextBillingDate.toString())
        // Framework dispatch supplies a real PendingResult so quick finish is observable.
        val filter = IntentFilter(intent.action).apply { addDataScheme("lifemanager") }
        context.registerReceiver(receiver, filter)
        try {
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
        } finally { context.unregisterReceiver(receiver) }
        val receiverShadow = shadowOf(receiver)
        assertTrue(receiverShadow.wentAsync(), "Valid legacy payload must enter the real receiver")
        return shadowOf(assertNotNull(receiverShadow.originalPendingResult))
    }

    private fun assertRejected() {
        assertEquals(0, notifications.size(), "Rejected arrival must not post")
        assertEquals(emptyList(), entityReads, "Rejected arrival must not read subscription rows")
        assertEquals(emptyList(), reminderReads, "Rejected arrival must not read reminder rows")
        assertEquals(0, repositoryResolutions, "Rejected arrival must not resolve its repository")
        assertEquals(0, schedulerResolutions, "Rejected arrival must not resolve its scheduler")
        assertEquals(0, alarmOperations, "Rejected arrival must not schedule or cancel alarms")
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    private fun TestScope.settleBroadcast(pending: ShadowBroadcastPendingResult) {
        // Room uses real executors; drain continuations without advancing the runner's 8 s clock.
        runCurrent()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!pending.future.isDone && System.nanoTime() < deadline) {
            Thread.sleep(10)
            runCurrent()
        }
        assertTrue(pending.future.isDone, "Receiver must finish before closing its Room database")
    }
}
