package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.orchestration.CarPlayController

internal object LeapmotorMediaKeys {
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var controller: CarPlayController? = null
    private var registered = false
    private var lastButton: Int? = null
    private var lastAt = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val id = LeapmotorWheelLearning.signature(intent) ?: return
            if (LeapmotorWheelLearning.capture(id)) return
            val button = context?.let { LeapmotorWheelLearning.button(it, id) }
                ?: LeapmotorMediaProtocol.button(LeapmotorMediaProtocol.action(intent)) ?: return
            val now = SystemClock.uptimeMillis()
            if (button == lastButton && now - lastAt < 250) return
            lastButton = button; lastAt = now
            val active = controller ?: return
            if (!CarPlayVideo.onMediaKey(button)) active.sendMediaButton(button)
            Log.i("DiPlay-LeapmotorKeys", "source=${intent.action} button=$button")
        }
    }

    fun attach(app: Context, next: CarPlayController) {
        if (!LeapmotorC11.detected()) return
        main.post {
            if (controller !== next) { lastButton = null; lastAt = 0 }
            controller = next
            ensureReceiver(app)
        }
    }

    /** Also listens while the settings screen captures a binding, without a running CarPlay session. */
    fun ensureReceiver(app: Context) {
        if (registered || !LeapmotorC11.detected()) return
        context = app.applicationContext
        val filter = IntentFilter().apply { LeapmotorMediaProtocol.actions.forEach(::addAction) }
        runCatching {
            ContextCompat.registerReceiver(context!!, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }.onFailure { Log.w("DiPlay-LeapmotorKeys", "Cannot register media receiver", it) }
    }

    fun detach(expected: CarPlayController?) {
        main.post {
            if (controller !== expected) return@post
            controller = null
            stopIfIdle()
        }
    }

    fun stopIfIdle() {
        if (controller != null || LeapmotorWheelLearning.isCapturing) return
        if (registered) runCatching { context?.unregisterReceiver(receiver) }
        registered = false; context = null
    }
}
