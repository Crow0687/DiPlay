package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local bindings for vendor broadcasts and Android keys; capture consumes the next event. */
internal object LeapmotorWheelLearning {
    private val main = Handler(Looper.getMainLooper())
    private var captured: ((String) -> Unit)? = null
    private var captureOwner: Context? = null
    private var cancelCapture: Runnable? = null
    private val consumedKeys = mutableSetOf<Pair<Int, Int>>()
    val isCapturing get() = captured != null
    private const val PREFS = "wheel-learning"
    private const val BINDINGS = "bindings-v1"
    private val choices = listOf("NEXT", "PREVIOUS", "PLAY", "PAUSE", "TOGGLE")
    private val buttons = listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS,
        CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.PLAY_PAUSE)

    fun signature(intent: Intent): String? {
        if (intent.action !in LeapmotorMediaProtocol.actions) return null
        if (LeapmotorMediaProtocol.selfOriginated(intent)) return null
        val action = LeapmotorMediaProtocol.action(intent)
        if (action != null) return "bus:${intent.action}:$action"
        // Only stable scalar fields, never serialized byte arrays or arbitrary objects.
        val fields = runCatching { intent.extras?.keySet()?.sorted()?.mapNotNull { key ->
            @Suppress("DEPRECATION") val value = intent.extras?.get(key)
            if (value is String || value is Number || value is Boolean) "$key=$value" else null
        }?.joinToString(";") }.getOrNull().orEmpty()
        return if (fields.isBlank() || fields.length > 512) null else "bus:${intent.action}:$fields"
    }

    fun capture(id: String): Boolean {
        val done = captured ?: return false
        captured = null
        main.post { done(id); LeapmotorMediaKeys.stopIfIdle() }
        return true
    }

    private fun bindings(context: Context): JSONArray = runCatching {
        val text = context.getSharedPreferences(PREFS, 0).getString(BINDINGS, "[]").orEmpty()
        if (text.length > 4096) JSONArray() else JSONArray(text)
    }.getOrElse { JSONArray() }

    fun button(context: Context, id: String): Int? {
        val bindings = bindings(context)
        for (i in 0 until bindings.length()) {
            val item = bindings.optJSONObject(i) ?: continue
            if (item.optString("id") != id) continue
            val index = choices.indexOf(item.optString("action"))
            return buttons.getOrNull(index)
        }
        return null
    }

    internal fun save(context: Context, id: String, action: String): Boolean {
        if (id.isBlank() || id.length > 600 || action !in choices) return false
        val old = bindings(context)
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("id") != id) next.put(item)
        }
        next.put(JSONObject().put("id", id).put("action", action))
        if (next.toString().length > 4096) return false
        context.getSharedPreferences(PREFS, 0).edit().putString(BINDINGS, next.toString()).apply()
        return true
    }

    fun onKey(context: Context, event: KeyEvent): Boolean {
        if (!LeapmotorC11.detected()) return false
        val physical = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_UP) return consumedKeys.remove(physical)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val id = "key:${event.keyCode}:${if (event.isLongPress) "long" else "short"}"
        if (capture(id)) { consumedKeys += physical; return true }
        val button = button(context, id) ?: return false
        if (event.repeatCount == 0 || event.isLongPress) CarPlayMediaKeys.onExternalMediaKey(button)
        consumedKeys += physical
        return true
    }

    fun show(context: Context) {
        val labels = context.resources.getStringArray(R.array.leapmotor_wheel_actions)
        AlertDialog.Builder(context).setTitle(R.string.settings_leapmotor_wheel_learning)
            .setItems(labels) { _, index -> begin(context, choices[index]) }
            .setNeutralButton(R.string.settings_leapmotor_clear_bindings) { _, _ ->
                context.getSharedPreferences(PREFS, 0).edit().remove(BINDINGS).apply()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun begin(context: Context, action: String) {
        cancelCapture?.run()
        captureOwner = context
        var wait: AlertDialog? = null
        val cancel = Runnable { captured = null; captureOwner = null; wait?.dismiss(); LeapmotorMediaKeys.stopIfIdle() }
        cancelCapture = cancel
        captured = onCaptured@ { id ->
            if (captureOwner !== context) return@onCaptured
            main.removeCallbacks(cancel)
            cancelCapture = null; captureOwner = null
            wait?.dismiss()
            val result = if (save(context, id, action)) R.string.settings_leapmotor_binding_saved
                else R.string.settings_leapmotor_binding_failed
            AlertDialog.Builder(context).setMessage(result).setPositiveButton(android.R.string.ok, null).show()
        }
        LeapmotorMediaKeys.ensureReceiver(context)
        wait = AlertDialog.Builder(context).setMessage(R.string.settings_leapmotor_press_key)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel.run() }
            .setOnCancelListener { cancel.run() }.create()
        wait.setOnKeyListener { _, _, event -> onKey(context, event) }
        wait.show()
        main.postDelayed(cancel, 15_000)
    }

    fun cancel(context: Context) {
        if (captureOwner !== context) return
        cancelCapture?.let { main.removeCallbacks(it); it.run() }
        cancelCapture = null
    }
}
