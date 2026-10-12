package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

internal object LeapmotorGearMonitor {
    const val RESPONSE_ACTION = "leapmotor.cantoapp.update"
    const val GEAR_EXTRA = "1010105"
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var controller: CarPlayController? = null
    private var registered = false
    @Volatile private var rawGear = -1
    private var pausedByReverse = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RESPONSE_ACTION) return
            val gear = runCatching { intent.getIntExtra(GEAR_EXTRA, -1) }.getOrDefault(-1)
            if (gear == -1 || rawGear == gear) return
            rawGear = gear
            if (gear == 1) {
                if (!pausedByReverse) pausedByReverse = controller?.sendMediaButton(CarPlayMediaButton.PAUSE) == true
            } else if (pausedByReverse) {
                pausedByReverse = false
                controller?.sendMediaButton(CarPlayMediaButton.PLAY)
            }
            Log.i("DiPlay-Gear", "gear=$gear pausedByReverse=$pausedByReverse")
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            val app = context ?: return
            if (controller == null) return
            runCatching { app.sendBroadcast(Intent("leapmotor.apptocan.update")
                .setPackage("com.leapmotor.carcanserver").putExtra("upgradeAsk", "upgrade")) }
            main.postDelayed(this, 1000)
        }
    }

    fun attach(app: Context, next: CarPlayController) {
        if (!LeapmotorC11.detected()) return
        main.post {
            if (controller === next) return@post
            stop()
            context = app.applicationContext
            controller = next
            runCatching {
                ContextCompat.registerReceiver(context!!, receiver, IntentFilter(RESPONSE_ACTION), ContextCompat.RECEIVER_EXPORTED)
                registered = true
                main.post(poll)
            }.onFailure { Log.w("DiPlay-Gear", "Cannot register gear receiver", it); stop() }
        }
    }

    fun detach(expected: CarPlayController?) {
        main.post { if (controller === expected) stop() }
    }

    private fun stop() {
        main.removeCallbacks(poll)
        if (registered) runCatching { context?.unregisterReceiver(receiver) }
        registered = false
        context = null
        controller = null
        rawGear = -1
        pausedByReverse = false
    }

    fun videoAllowed(): Boolean? = parkedForGear(rawGear)
    internal fun parkedForGear(gear: Int): Boolean? = if (gear == -1) null else gear == 2
}
