package com.example.lifemanager.ui.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Simulated OS boundaries, not proof of a real device granting permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsPlatformSectionBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    @Config(sdk = [28])
    fun preAndroid13StillReportsAppWideNotificationBlocking() {
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        show()
        compose.onNodeWithText("应用通知：已关闭").assertExists()
        compose.onNodeWithText("申请通知权限").assertDoesNotExist()
        compose.onNodeWithText("精确提醒：此系统无需单独授权").assertExists()
        compose.onNodeWithText("精确提醒设置").assertDoesNotExist()
    }

    @Test
    fun resumeRefreshesBothPermissionsAfterExternalRevocation() {
        val owner = TestLifecycleOwner()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        show(owner)
        compose.onNodeWithText("应用通知：已开启").assertExists()
        compose.onNodeWithText("精确提醒：已允许").assertExists()
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("通知权限：未允许").assertExists()
        compose.onNodeWithText("应用通知：已关闭").assertExists()
        compose.onNodeWithText("精确提醒：未允许，提醒可能延迟").assertExists()
    }

    @Test
    fun permissionResultRefreshesSystemStateWithoutAnotherResume() {
        val registry = ResultRegistry()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show(registry = registry)
        compose.onNodeWithText("申请通知权限").performScrollTo().performClick()
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, registry.requestedPermission)
        compose.runOnIdle {
            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
            registry.dispatchResult(assertNotNull(registry.requestCode), true)
        }
        compose.onNodeWithText("通知权限：已允许").assertExists()
        compose.onNodeWithText("应用通知：已开启").assertExists()
        compose.onNodeWithText("申请通知权限").assertDoesNotExist()
    }

    @Test
    fun callbackBooleanCannotFabricatePermissionSuccess() {
        val registry = ResultRegistry()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        show(registry = registry)
        compose.onNodeWithText("申请通知权限").performScrollTo().performClick()
        compose.runOnIdle { registry.dispatchResult(assertNotNull(registry.requestCode), true) }
        compose.onNodeWithText("通知权限：未允许").assertExists()
        compose.onNodeWithText("应用通知：已关闭").assertExists()
        compose.onNodeWithText("通知尚未开启，可在系统通知设置中调整。离线数据操作不受影响。").assertExists()
    }

    @Test
    fun deniedCallbackKeepsSettingsRecoveryAvailable() {
        val registry = ResultRegistry()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show(registry = registry)
        compose.onNodeWithText("申请通知权限").performScrollTo().performClick()
        compose.runOnIdle { registry.dispatchResult(assertNotNull(registry.requestCode), false) }
        compose.onNodeWithText("通知权限：未允许").assertExists()
        compose.onNodeWithText("系统通知设置").performScrollTo().assertIsEnabled()
    }

    @Test
    fun notificationSettingsTargetsOnlyThisApplication() {
        var launched: Intent? = null
        show(startActivity = { launched = it })
        compose.onNodeWithText("系统通知设置").performScrollTo().performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, assertNotNull(launched).action)
        assertEquals(application.packageName, launched!!.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun exactAlarmSettingsTargetsOnlyThisApplication() {
        var launched: Intent? = null
        show(startActivity = { launched = it })
        compose.onNodeWithText("精确提醒设置").performScrollTo().performClick()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, assertNotNull(launched).action)
        assertEquals("package:${application.packageName}", launched!!.dataString)
    }

    @Test
    fun missingSettingsActivityShowsRecoveryMessage() {
        show(startActivity = { throw ActivityNotFoundException() })
        compose.onNodeWithText("系统通知设置").performScrollTo().performClick()
        compose.onNodeWithText("此设备未提供该设置入口，请在系统设置中找到本应用并调整权限。离线数据操作不受影响。")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun restrictedSettingsActivityShowsRecoveryMessage() {
        show(startActivity = { throw SecurityException() })
        compose.onNodeWithText("精确提醒设置").performScrollTo().performClick()
        compose.onNodeWithText("系统限制了此操作，请在系统设置中检查本应用权限。离线数据操作不受影响。")
            .performScrollTo().assertIsDisplayed()
    }

    private fun show(
        owner: LifecycleOwner? = null,
        registry: ResultRegistry? = null,
        startActivity: ((Intent) -> Unit)? = null,
    ) {
        compose.setContent {
            val original = LocalContext.current
            val context = if (startActivity == null) original else object : ContextWrapper(original) {
                override fun startActivity(intent: Intent) = startActivity(intent)
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current),
                LocalActivityResultRegistryOwner provides (registry ?: requireNotNull(LocalActivityResultRegistryOwner.current)),
            ) {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SettingsPlatformSection() }
                }
            }
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class ResultRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        var requestCode: Int? = null
        var requestedPermission: Any? = null
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            requestedPermission = input
        }
    }
}
