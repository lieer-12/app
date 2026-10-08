package com.example.lifemanager.notification

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, shadows = [RevokingAlarmService::class])
class ReminderAlarmPermissionTest {
    @Before fun reset() { RevokingAlarmService.permission = true; RevokingAlarmService.failQuery = false; RevokingAlarmService.failInexact = false; RevokingAlarmService.inexactAt = null }
    @Test fun deniedPermissionUsesInexact() {
        RevokingAlarmService.permission = false
        schedule()
        assertEquals(123456L, RevokingAlarmService.inexactAt)
    }
    @Test fun revokedPermissionAfterSuccessfulQueryUsesInexact() {
        schedule()
        assertEquals(123456L, RevokingAlarmService.inexactAt)
    }
    @Test fun permissionQuerySecurityFailureAlsoUsesInexact() {
        RevokingAlarmService.failQuery = true
        schedule()
        assertEquals(123456L, RevokingAlarmService.inexactAt)
    }
    @Test fun failedFallbackIsReportedRatherThanClaimingAlarmWasScheduled() {
        RevokingAlarmService.failInexact = true
        assertFailsWith<IllegalStateException> { schedule() }
    }
    private fun schedule() {
        val context = RuntimeEnvironment.getApplication()
        val operation = PendingIntent.getBroadcast(context, 1, Intent(context, TodoReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setReminder(123456L, operation)
    }
}

/** Fault injection at the OS boundary; the production fallback logic remains real. */
@Implements(AlarmManager::class)
class RevokingAlarmService {
    @Implementation fun canScheduleExactAlarms(): Boolean {
        if (failQuery) throw SecurityException("revoked during query")
        return permission
    }
    @Implementation fun setExactAndAllowWhileIdle(type: Int, at: Long, operation: PendingIntent) {
        throw SecurityException("revoked during set")
    }
    @Implementation fun setAndAllowWhileIdle(type: Int, at: Long, operation: PendingIntent) {
        if (failInexact) error("alarm service unavailable")
        inexactAt = at
    }
    companion object {
        var permission = true
        var failQuery = false
        var failInexact = false
        var inexactAt: Long? = null
    }
}
