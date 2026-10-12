package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeapmotorSettingsScreenshotsTest {
    @Test @Config(qualifiers = "en-w320dp-h800dp-xhdpi")
    fun compactLargeFont() = render("compact", 320)
    @Test @Config(qualifiers = "en-w800dp-h480dp-xhdpi")
    fun fullLargeFont() = render("full", 800)
    @Test @Config(qualifiers = "ar-ldrtl-w600dp-h800dp-xhdpi")
    fun arabicLargeFont() = render("full-ar", 600)

    private fun render(name: String, widthDp: Int) {
        val previous = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/test")
        try {
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            activity.setTheme(android.R.style.Theme_Material_NoActionBar)
            val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.WHEEL_KEYS))
            val content = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
            val button = descendants(content).filterIsInstance<TextView>()
                .single { it.text == activity.getString(R.string.settings_leapmotor_wheel_learning) }
            val row = button.parent as ViewGroup
            row.layoutDirection = configuration.layoutDirection
            val width = (widthDp * activity.resources.displayMetrics.density).toInt()
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, width, row.measuredHeight)
            assertTrue(button.left >= 0 && button.right <= row.width && button.bottom <= row.height)
            val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
            row.draw(Canvas(bitmap))
            val directory = File("build/leapmotor-screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.CLUSTER_MAP))
            val maps = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, maps))
            val toggle = descendants(maps).filterIsInstance<android.widget.Switch>()
                .single { it.contentDescription == activity.getString(R.string.settings_leapmotor_showmap) }
            assertTrue(!toggle.isChecked)
            val mapRow = toggle.parent as ViewGroup
            mapRow.layoutDirection = configuration.layoutDirection
            mapRow.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            mapRow.layout(0, 0, width, mapRow.measuredHeight)
            assertTrue(toggle.left >= 0 && toggle.right <= width && toggle.bottom <= mapRow.height)
            val mapBitmap = Bitmap.createBitmap(width, mapRow.height, Bitmap.Config.ARGB_8888)
            mapRow.draw(Canvas(mapBitmap))
            File(directory, "$name-overlay.png").outputStream().use { assertTrue(mapBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            mapBitmap.recycle()
        } finally { ShadowBuild.setFingerprint(previous) }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
