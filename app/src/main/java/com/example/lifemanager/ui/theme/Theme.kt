package com.example.lifemanager.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lifemanager.domain.model.ThemeMode

// Preset Material 3 roles keep the approved stationery palette independent of wallpaper.
private val LightColors = lightColorScheme(
    primary = Color(0xFF246956), onPrimary = Color.White,
    primaryContainer = Color(0xFFBDE9D4), onPrimaryContainer = Color(0xFF153D31),
    secondary = Color(0xFF8D452F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFB69F), onSecondaryContainer = Color(0xFF4E2418),
    tertiary = Color(0xFF67571B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6D878), onTertiaryContainer = Color(0xFF463B12),
    background = Color(0xFFFFF8ED), onBackground = Color(0xFF202E28),
    surface = Color(0xFFFFFCF5), onSurface = Color(0xFF202E28),
    surfaceVariant = Color(0xFFE7EEE5), onSurfaceVariant = Color(0xFF526258),
    outline = Color(0xFF75847A), outlineVariant = Color(0xFFCFD9CF),
    error = Color(0xFF9F2F2F), onError = Color.White,
    errorContainer = Color(0xFFFFDBD4), onErrorContainer = Color(0xFF5E1515),
    inverseSurface = Color(0xFF2C3430), inverseOnSurface = Color(0xFFF8F5E9),
    inversePrimary = Color(0xFFBDE9D4), scrim = Color(0xFF17241D),
    surfaceDim = Color(0xFFE9E3D8), surfaceBright = Color(0xFFFFFCF5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCF5),
    surfaceContainer = Color(0xFFF5EFE5), surfaceContainerHigh = Color(0xFFEFE9DF),
    surfaceContainerHighest = Color(0xFFE9E3D8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDE9D4), onPrimary = Color(0xFF153D31),
    primaryContainer = Color(0xFF315D4E), onPrimaryContainer = Color(0xFFE3F8ED),
    secondary = Color(0xFFFFB69F), onSecondary = Color(0xFF4E2418),
    secondaryContainer = Color(0xFF74432F), onSecondaryContainer = Color(0xFFFFE1D5),
    tertiary = Color(0xFFF6D878), onTertiary = Color(0xFF463B12),
    tertiaryContainer = Color(0xFF564814), onTertiaryContainer = Color(0xFFFFE393),
    background = Color(0xFF202522), onBackground = Color(0xFFF8F5E9),
    surface = Color(0xFF2C3430), onSurface = Color(0xFFF8F5E9),
    surfaceVariant = Color(0xFF3A463F), onSurfaceVariant = Color(0xFFC4D1C6),
    outline = Color(0xFF93A297), outlineVariant = Color(0xFF4C5C51),
    error = Color(0xFFFFB4AB), onError = Color(0xFF601410),
    errorContainer = Color(0xFF76302A), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFF8F5E9), inverseOnSurface = Color(0xFF202E28),
    inversePrimary = Color(0xFF246956), scrim = Color.Black,
    surfaceDim = Color(0xFF202522), surfaceBright = Color(0xFF3A463F),
    surfaceContainerLowest = Color(0xFF171D19), surfaceContainerLow = Color(0xFF262F29),
    surfaceContainer = Color(0xFF2C3430), surfaceContainerHigh = Color(0xFF333E37),
    surfaceContainerHighest = Color(0xFF3A463F),
)

private val PlannerTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val PlannerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LifeManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode? = null,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM, null -> darkTheme
    }
    val colors = if (useDarkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findActivity()?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDarkTheme
            controller.isAppearanceLightNavigationBars = !useDarkTheme
            // Android 15 enforces transparent bars; earlier systems need matching backgrounds.
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.statusBarColor = colors.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colors.background.toArgb()
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PlannerTypography,
        shapes = PlannerShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
