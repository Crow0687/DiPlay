package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/** Legacy Autonavi showmap wire protocol; opt-in and only active on the detected C11. */
internal object LeapmotorShowmapOverlay {
    const val KEY = "leapmotor-showmap"
    private var owner: Context? = null
    private var root: View? = null
    private var surface: Surface? = null
    private var layout: CarPlayVideoLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var sink: ((Surface?) -> Unit)? = null
    private var touch: ((List<AirPlayContact>) -> Unit)? = null
    private var release: ((Surface, SurfaceTexture) -> Unit)? = null
    fun enabled(context: Context) = context.getSharedPreferences("leapmotor", 0).getBoolean("showmap", false)
    fun saveEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("leapmotor", 0).edit().putBoolean("showmap", value).apply()
        if (!value) hide()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val app = owner ?: return
            if (!enabled(app)) return
            when (intent?.action) {
                "com.autonavi.plus.closemap" -> hide()
                "com.autonavi.plus.showmap" -> runCatching {
                    show(app, intent.getIntExtra("x", Int.MIN_VALUE), intent.getIntExtra("y", Int.MIN_VALUE),
                        intent.getIntExtra("w", 0), intent.getIntExtra("h", 0))
                }.onFailure { Log.w("DiPlay-Showmap", "Invalid showmap request", it) }
            }
        }
    }
    fun attach(context: Context, apply: (Surface?) -> Unit, sendTouch: (List<AirPlayContact>) -> Unit,
        releaseSurface: (Surface, SurfaceTexture) -> Unit) {
        if (!LeapmotorC11.detected()) return
        owner?.let(::detach)
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction("com.autonavi.plus.showmap"); addAction("com.autonavi.plus.closemap")
            }, ContextCompat.RECEIVER_EXPORTED)
            owner = context; sink = apply; touch = sendTouch; release = releaseSurface
        }.onFailure { Log.w("DiPlay-Showmap", "Cannot register showmap receiver", it) }
    }
    fun detach(context: Context) {
        if (owner !== context) return
        hide()
        runCatching { context.unregisterReceiver(receiver) }
        owner = null; sink = null; touch = null; release = null
    }
    fun currentSurface(): Surface? = surface
    fun reapply() { surface?.let { sink?.invoke(it) } }
    private fun hide() {
        val context = owner
        root?.let { view -> runCatching { context?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) } }
        root = null; params = null; layout = null
        // The media sink owns decoder detachment; keep release after the window's destruction callback.
    }
    private fun show(context: Context, x: Int, y: Int, width: Int, height: Int) {
        if (!Settings.canDrawOverlays(context)) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        val previous = params
        val w = (if (width > 0) width else previous?.width ?: (metrics.widthPixels / 3)).coerceIn(1, metrics.widthPixels)
        val h = (if (height > 0) height else previous?.height ?: (w / (16.0 / 9)).toInt()).coerceIn(1, metrics.heightPixels)
        val next = WindowManager.LayoutParams(w, h,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = (if (x == Int.MIN_VALUE) previous?.x ?: (metrics.widthPixels - w) else x).coerceIn(0, metrics.widthPixels - w)
            this.y = (if (y == Int.MIN_VALUE) previous?.y ?: ((metrics.heightPixels - h) / 2) else y).coerceIn(0, metrics.heightPixels - h)
            title = "DiPlay showmap"
        }
        root?.let { wm.updateViewLayout(it, next); params = next; return }
        val video = TextureView(context)
        val releaseOutput = release
        var ownedSurface: Surface? = null
        fun crop(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val aspect = width.toDouble() / height
            val sx = if (aspect < 16.0 / 9) ((16.0 / 9) / aspect).toFloat() else 1f
            val sy = if (aspect > 16.0 / 9) (aspect / (16.0 / 9)).toFloat() else 1f
            video.setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
            layout = CarPlayVideoLayout((1 - sx) * width / 2, (1 - sy) * height / 2, width * sx, height * sy)
        }
        video.setOnTouchListener { _, event -> layout?.let { touch?.invoke(CarPlayTouchMapper.contacts(event, it)) }; true }
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                crop(width, height)
                ownedSurface = Surface(texture)
                surface = ownedSurface
                sink?.invoke(ownedSurface)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop(width, height)
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val old = ownedSurface
                ownedSurface = null
                if (surface === old) { surface = null; layout = null }
                if (old != null && releaseOutput != null) { releaseOutput(old, texture); return false }
                old?.release()
                return true
            }
        }
        val view = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK); addView(video, FrameLayout.LayoutParams(-1, -1)) }
        wm.addView(view, next); root = view; params = next
    }
}
