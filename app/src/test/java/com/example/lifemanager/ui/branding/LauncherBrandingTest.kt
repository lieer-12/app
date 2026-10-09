package com.example.lifemanager.ui.branding

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the installed manifest/resource boundary used by launchers, not source XML text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherBrandingTest {
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun launcherShowsApprovedNameAndKeepsExistingInstallIdentity() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(application.packageName)
        val resolved = assertNotNull(application.packageManager.resolveActivity(intent, 0))
        assertEquals("com.example.lifemanager", resolved.activityInfo.packageName)
        assertEquals("com.example.lifemanager.MainActivity", resolved.activityInfo.name)
        assertEquals("日子芽", resolved.loadLabel(application.packageManager).toString())
    }

    @Test
    fun launcherLoadsCustomAdaptiveArtworkInsteadOfSystemPlaceholder() {
        val icon = launcherIcon()
        assertTrue(icon is AdaptiveIconDrawable, "launcher still uses the system placeholder")
        assertNotNull(icon.foreground)
        assertNotNull(icon.background)
        val image = render(icon.foreground)
        assertTrue(opaquePixels(image) > image.width * image.height / 10, "foreground is empty or unreadably small")
        assertEquals(0, Color.alpha(image.getPixel(0, 0)), "foreground includes a square backing")
    }

    @Test
    fun foregroundStaysInsideCircularSafeZoneWithoutCroppingSproutOrBook() {
        val icon = launcherIcon()
        assertTrue(icon is AdaptiveIconDrawable, "no adaptive foreground to crop")
        val image = render(icon.foreground)
        val center = image.width / 2.0
        val radius = image.width * 33.0 / 108.0
        var visiblePixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (Color.alpha(image.getPixel(x, y)) > 8) {
                visiblePixels++
                assertTrue(hypot(x + 0.5 - center, y + 0.5 - center) <= radius + 1.0,
                    "artwork at ($x, $y) exceeds circular safe zone")
            }
        }
        assertTrue(visiblePixels > 0, "safe-zone check must not pass on empty artwork")
    }

    @Test
    @Config(sdk = [33])
    fun themedLauncherHasNonemptyMonochromeMarkWithTransparentMargins() {
        val icon = launcherIcon()
        assertTrue(icon is AdaptiveIconDrawable)
        val mono = assertNotNull(icon.monochrome, "Android 13 themed icon layer is missing")
        val image = render(mono)
        assertTrue(opaquePixels(image) > image.width * image.height / 10)
        assertEquals(0, Color.alpha(image.getPixel(0, 0)), "themed icon becomes a solid square")
    }

    private fun launcherIcon(): Drawable {
        val info = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
        assertTrue(info.icon != 0, "manifest does not register this app's own launcher icon")
        return info.loadIcon(application.packageManager)
    }

    private fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, it.width, it.height)
        drawable.draw(Canvas(it))
    }

    private fun opaquePixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 8) count++
        }
        return count
    }
}
