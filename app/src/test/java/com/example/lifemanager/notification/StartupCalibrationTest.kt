package com.example.lifemanager.notification

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.TestWorkManagerImpl
import androidx.work.impl.close
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.example.lifemanager.LifeManagerApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import kotlin.test.assertEquals

/** Real Application/WorkManager wiring; never writes business rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StartupCalibrationTest {
    private lateinit var context: LifeManagerApp
    private lateinit var manager: WorkManagerImpl
    @Before fun setup() {
        context = LifeManagerApp()
        // Robolectric does not run the merged AndroidX Startup provider before onCreate.
        // Attach the real application, then initialize WorkManager as that provider does.
        val base = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = context
        }
        ReflectionHelpers.setField(context, "mBase", base)
        val configuration = Configuration.Builder().build()
        manager = TestWorkManagerImpl(context, configuration, WorkManagerTaskExecutor(configuration.taskExecutor))
        WorkManagerImpl.setDelegate(manager)
    }

    @After fun cleanup() {
        if (::manager.isInitialized) manager.close()
        WorkManagerImpl.setDelegate(null)
    }

    @Test fun startupEnqueuesFreshCalibrationWithoutWaitingForTheDailyPeriod() {
        context.onCreate()
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("reminder_reconciliation_immediate").get()
        assertEquals(1, work.size, "Startup must enqueue a fresh current-data calibration")
    }

    @Test fun systemEventsRetainFreshCalibrationPassesWithoutBroadcastLifetimeWork(): Unit = runBlocking {
        val coordinator = EntryPointAccessors.fromApplication(context, TodoReminderEntryPoint::class.java).maintenanceCoordinator()
        coordinator.withSession {
            // The shadow dispatch establishes a real PendingResult, not a null test-only goAsync.
            val receiver = BootReceiver()
            val shadow = org.robolectric.Shadows.shadowOf(receiver)
            shadow.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED), java.util.concurrent.atomic.AtomicBoolean(false))
            shadow.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED), java.util.concurrent.atomic.AtomicBoolean(false))
            assertEquals(false, shadow.wentAsync(), "Boot delegates to durable work; it must not hold a broadcast across database reads")
            val work = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("reminder_reconciliation_immediate").get()
            assertEquals(2, work.size, "System events retain serialized fresh passes rather than dropping a request or replaying payloads")
        }
    }
}
