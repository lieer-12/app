package com.example.lifemanager.ui.branding

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** OS resource/manifest verification only; never inserts records or clears application data. */
@RunWith(AndroidJUnit4::class)
class LauncherBrandingDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun installedLauncherUsesNewNameAndExistingPackage() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val target = assertNotNull(context.packageManager.resolveActivity(intent, 0))
        assertEquals("com.example.lifemanager", target.activityInfo.packageName)
        assertEquals("日子芽", target.loadLabel(context.packageManager).toString())
        assertTrue(target.activityInfo.applicationInfo.icon != 0)
    }

    @Test
    fun normalAndRoundLaunchersLoadAdaptiveLayersWithoutSquareForeground() {
        val icon = adaptiveIcon()
        checkArtwork(icon.foreground)
        val roundId = context.resources.getIdentifier("ic_launcher_round", "mipmap", context.packageName)
        assertTrue(roundId != 0, "round launcher resource is missing")
        val round = assertNotNull(context.getDrawable(roundId))
        assertTrue(round is AdaptiveIconDrawable)
        checkArtwork(round.foreground)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun themedIconHasTransparentFaceCutoutsAndSafeMargins() {
        val mono = assertNotNull(adaptiveIcon().monochrome)
        val image = checkArtwork(mono)
        // Hand-derived centers of the two eyes after the artwork's -5 degree rotation.
        assertEquals(0, Color.alpha(image.getPixel(145, 173)), "left eye is no longer a cutout")
        assertEquals(0, Color.alpha(image.getPixel(187, 169)), "right eye is no longer a cutout")
    }

    private fun adaptiveIcon(): AdaptiveIconDrawable {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue(info.icon != 0)
        val icon = info.loadIcon(context.packageManager)
        assertTrue(icon is AdaptiveIconDrawable)
        return icon
    }

    private fun checkArtwork(drawable: Drawable): Bitmap {
        val image = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, image.width, image.height)
        drawable.draw(Canvas(image))
        assertEquals(0, Color.alpha(image.getPixel(0, 0)))
        var visible = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (Color.alpha(image.getPixel(x, y)) > 8) {
                visible++
                assertTrue(hypot(x + 0.5 - 162.0, y + 0.5 - 162.0) <= 100.0,
                    "artwork at ($x, $y) exceeds the central 66dp circle")
            }
        }
        assertTrue(visible > image.width * image.height / 10, "icon is empty or too small")
        return image
    }
}
