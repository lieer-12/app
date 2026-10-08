package com.example.lifemanager.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.lifemanager.domain.model.ThemeMode
import com.example.lifemanager.ui.theme.LifeManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsThemeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun explicitDarkSelectionOverridesLightSystem() {
        var luminance = Float.NaN
        compose.setContent { LifeManagerTheme(darkTheme = false, themeMode = ThemeMode.DARK) {
            val surface = MaterialTheme.colorScheme.surface
            SideEffect { luminance = surface.luminance() }
            Text("主题")
        } }
        compose.runOnIdle { assertTrue(luminance < 0.5f, "explicit dark mode still uses light surfaces") }
    }
    @Test fun explicitLightSelectionOverridesDarkSystem() {
        var luminance = Float.NaN
        compose.setContent { LifeManagerTheme(darkTheme = true, themeMode = ThemeMode.LIGHT) {
            val surface = MaterialTheme.colorScheme.surface
            SideEffect { luminance = surface.luminance() }
            Text("主题")
        } }
        compose.runOnIdle { assertTrue(luminance > 0.5f, "explicit light mode still uses dark surfaces") }
    }
    @Test fun systemSelectionRespondsToSystemChanges() {
        var luminance = Float.NaN
        val systemDark = mutableStateOf(false)
        compose.setContent { LifeManagerTheme(darkTheme = systemDark.value, themeMode = ThemeMode.SYSTEM) {
            val surface = MaterialTheme.colorScheme.surface
            SideEffect { luminance = surface.luminance() }
            Text("主题")
        } }
        compose.runOnIdle { assertTrue(luminance > 0.5f); systemDark.value = true }
        compose.runOnIdle { assertTrue(luminance < 0.5f) }
    }

    // Flat identical surfaces erase the intended card hierarchy in the new visual theme.
    @Test fun lightCardsRemainDistinctFromPageAndBodyTextKeepsContrast() = checkHierarchy(false)
    @Test fun darkCardsRemainDistinctFromPageAndBodyTextKeepsContrast() = checkHierarchy(true)

    private fun checkHierarchy(dark: Boolean) {
        var background = Float.NaN
        var surface = Float.NaN
        var foreground = Float.NaN
        compose.setContent { LifeManagerTheme(darkTheme = dark) {
            val colors = MaterialTheme.colorScheme
            SideEffect {
                background = colors.background.luminance()
                surface = colors.surface.luminance()
                foreground = colors.onSurface.luminance()
            }
            Text("正文")
        } }
        compose.runOnIdle {
            assertTrue(surface > background, "cards must be visibly lifted from the page")
            val contrast = (maxOf(surface, foreground) + 0.05f) / (minOf(surface, foreground) + 0.05f)
            assertTrue(contrast >= 4.5f, "body text must retain at least 4.5:1 contrast")
        }
    }

    // Light status icons disappear against the cream page on edge-to-edge Android windows.
    @Test fun themeChangeUpdatesSystemBarIconContrast() {
        val dark = mutableStateOf(false)
        var activity: Activity? = null
        compose.setContent { LifeManagerTheme(darkTheme = dark.value) {
            val view = LocalView.current
            SideEffect { activity = view.context as? Activity }
            Text("正文")
        } }
        compose.runOnIdle {
            val owner = requireNotNull(activity)
            val controller = WindowCompat.getInsetsController(owner.window, owner.window.decorView)
            assertTrue(controller.isAppearanceLightStatusBars, "cream pages need dark status icons")
            assertTrue(controller.isAppearanceLightNavigationBars, "cream pages need dark navigation icons")
            dark.value = true
        }
        compose.runOnIdle {
            val owner = requireNotNull(activity)
            val controller = WindowCompat.getInsetsController(owner.window, owner.window.decorView)
            assertTrue(!controller.isAppearanceLightStatusBars)
            assertTrue(!controller.isAppearanceLightNavigationBars)
        }
    }
}
