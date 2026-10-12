package com.shilapi.xcertplay

import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** Measured C11 canvas from the 0.2.15-245 APK; do not apply it to other cars. */
internal object LeapmotorC11 {
    fun detected(fingerprint: String = Build.FINGERPRINT) =
        fingerprint.contains("qti/msmnile_gvmq", ignoreCase = true)

    data class Viewport(val left: Int, val top: Int, val width: Int, val height: Int)

    fun viewport(width: Int, height: Int, fingerprint: String = Build.FINGERPRINT): Viewport? =
        if (detected(fingerprint) && width == 1920 && height == 720)
            Viewport(1200, 0, 720, 720) else null

    fun streamConfig() = AirPlayDisplayConfig(
        widthPixels = 720, heightPixels = 720, widthPhysicalMm = 292, heightPhysicalMm = 292,
        fps = 30, primaryInputDevice = 0, safeArea = AirPlayInsets(255, 39, 255, 255),
        safeAreaDrawOutside = true, initialUrl = CarPlayClusterDisplay.MAP_URL,
    )
}
