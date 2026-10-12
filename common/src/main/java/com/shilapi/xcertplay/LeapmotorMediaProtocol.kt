package com.shilapi.xcertplay

import android.content.Intent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.json.JSONObject
import java.util.Locale

/** Wire formats recovered from the C11 APK, with malformed/self-originated payloads rejected. */
internal object LeapmotorMediaProtocol {
    val actions = setOf(
        "car.meter.music.BROADCAST",
        "car.hmi.music.BROADCAST",
        "com.leapmotor.command.music",
        "com.leapmotor.command.multimedia",
        "com.leapmotor.command.media",
        "com.leapmotor.command.playpause",
        "com.leapmotor.command.next",
        "com.leapmotor.command.previous",
        "com.leapmotor.command.key",
        "com.leapmotor.command.mediaKey",
        "com.leapmotor.multimedia.mediaKey",
        "com.leapmotor.multimedia.MediaKey",
        "com.leapmotor.media.keyevent",
        "com.leapmotor.media.KEY_EVENT",
        "com.leapmotor.audio.key",
        "com.leapmotor.audio.KEY_EVENT",
        "com.leapmotor.audiocenter.mediaKey",
        "com.leapmotor.vehicle.keyevent",
        "com.leapmotor.vehicle.mediaKey",
        "car.hmi.media.KEY_EVENT",
        "car.hmi.keyevent",
        "car.media.keyevent",
        "car.media.next",
        "car.media.previous",
        "com.leapmotor.ICUMainCtrl",
        "com.leapmotor.ICUCtrl",
        "com.leapmotor.customkey.media.next",
        "com.leapmotor.customkey.media.prev",
        "com.leapmotor.customkey.media.pauseplay",
        "com.leapmotor.customkey.music.next",
        "com.leapmotor.customkey.music.prev",
        "com.leapmotor.command.netRadio",
        "com.leapmotor.command.fmradio",
        "com.leapmotor.music.netradio.search",
        "car.meter.query.BROADCAST",
        "com.leapmotor.pohone.toxmly.calling",
        "com.leapmotor.action.METER.CTRL",
        "com.leapmotor.action.VOICE.CTRL",
        "com.leapmotor.ICU2MMICtrl",
        "com.leapmotor.customkey.music.pauseplay",
    )
    fun actionFromJson(text: String?): String? = runCatching {
        val json = JSONObject(text?.trim().orEmpty())
        if (json.optString("src") == "diplay") return null
        val type = json.optString("type")
        if (type.isNotEmpty() && !type.equals("music", true)) return null
        json.optJSONObject("data")?.optString("action")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun actionFromPayload(payload: ByteArray?): String? {
        if (payload == null || payload.size <= 2) return null
        val end = minOf(payload.size, (payload[0].toInt() and 255) * 100 + (payload[1].toInt() and 255) + 2)
        if (end <= 2) return null
        return actionFromJson(String(payload, 2, end - 2, Charsets.UTF_8))
    }

    fun selfOriginated(intent: Intent): Boolean = runCatching {
        val payload = intent.getByteArrayExtra("receiver") ?: return false
        if (payload.size <= 2) return false
        JSONObject(String(payload, 2, payload.size - 2, Charsets.UTF_8)).optString("src") == "diplay"
    }.getOrDefault(false)

    fun action(intent: Intent): String? = runCatching {
        when (intent.action) {
            "com.leapmotor.action.METER.CTRL", "com.leapmotor.action.VOICE.CTRL" -> {
                if (intent.getStringExtra("keyEventType") !in setOf("steeringWheel", "backRow")) return null
                when (intent.getStringExtra("value")) { "1" -> "preOne"; "2" -> "nextOne"; "3" -> "playpause"; else -> null }
            }
            "com.leapmotor.ICU2MMICtrl", "com.leapmotor.customkey.music.pauseplay" -> {
                fun number(vararg keys: String): Int = keys.firstNotNullOfOrNull { key ->
                    intent.getIntExtra(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                } ?: 0
                when {
                    number("ICU_MediaKey", "mediaKey") == 1 -> "playpause"
                    number("ICU_MediaSwitch", "mediaSwitch") == 2 -> "nextOne"
                    number("ICU_MediaSwitch", "mediaSwitch") == 1 -> "preOne"
                    else -> null
                }
            }
            else -> {
                val payload = intent.getByteArrayExtra("receiver")
                // Never fall through to a second extra when the bus explicitly identifies our own message.
                if (payload != null && payload.size > 2) {
                    val json = runCatching { JSONObject(String(payload, 2, payload.size - 2, Charsets.UTF_8)) }.getOrNull()
                    if (json?.optString("src") == "diplay") return null
                }
                actionFromPayload(payload) ?: intent.getStringExtra("action")?.takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    fun button(action: String?): Int? = when (action?.trim()?.lowercase(Locale.ROOT)) {
        "next", "nexttrack", "nextone" -> CarPlayMediaButton.NEXT
        "previous", "preone", "previoustrack", "prev" -> CarPlayMediaButton.PREVIOUS
        "playpause", "toggleplay", "pauseplay", "play_pause", "toggleplaypause" -> CarPlayMediaButton.PLAY_PAUSE
        else -> null
    }
}
