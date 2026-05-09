package com.kidtime.tv

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * DEBUG ONLY: a tiny window that floats in the corner of the screen
 * even on top of YouTube/Netflix, so we can see the service state
 * in real time without leaving the app.
 *
 * This uses TYPE_APPLICATION_OVERLAY which requires the SYSTEM_ALERT_WINDOW
 * permission (already granted at setup).
 */
object DebugOverlay {
    private var view: TextView? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    fun attach(context: Context) {
        if (view != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)) {
            return  // Don't crash if perm missing
        }
        try {
            wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(context).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                setPadding(16, 8, 16, 8)
                textSize = 11f
                text = "KidTime: starting…"
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = 80
            }
            wm?.addView(tv, params)
            view = tv
        } catch (e: Exception) {
            android.util.Log.e("DebugOverlay", "attach failed", e)
        }
    }

    fun update(text: String) {
        handler.post {
            view?.text = text
        }
    }

    fun detach() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (e: Exception) {}
        view = null
    }
}
