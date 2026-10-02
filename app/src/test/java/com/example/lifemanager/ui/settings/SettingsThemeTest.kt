package com.example.lifemanager.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
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
}
