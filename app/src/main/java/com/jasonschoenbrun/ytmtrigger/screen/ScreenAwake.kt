package com.jasonschoenbrun.ytmtrigger.screen

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Keeps the display awake while music is playing, and only then.
 *
 * YouTube Music's free tier pauses anything you did not upload yourself as
 * soon as the screen sleeps - measured on device: the session goes to
 * `PAUSED` and the audio player is released within ten seconds, while an
 * uploaded playlist carries on untouched. The usual workaround is the
 * developer option "Stay awake", which lights the screen twenty-four hours a
 * day; this narrows it to the minutes music is actually playing.
 *
 * Implemented as a 1x1 transparent overlay carrying
 * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]. A window flag rather than
 * a wake lock because `SCREEN_BRIGHT_WAKE_LOCK` and `SCREEN_DIM_WAKE_LOCK`
 * have been deprecated since API 17 and are unreliable on modern Android,
 * while the `PARTIAL_WAKE_LOCK` this app already takes for triggers
 * explicitly does not keep the display on.
 *
 * The window can also pin brightness near zero, so the screen is technically
 * on but practically black. That is what makes holding it for an hour
 * reasonable rather than a burn-in risk.
 */
object ScreenAwake {

    /** Not fully zero: some devices treat 0f as "no override". */
    private const val DIM_BRIGHTNESS = 0.01f

    private var overlay: View? = null

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun isHeld(): Boolean = overlay != null

    /**
     * Hold or release the screen. Must run on the main thread, since it
     * touches the window manager.
     */
    fun apply(context: Context, keepOn: Boolean, dim: Boolean) {
        if (keepOn) acquire(context, dim) else release(context)
    }

    private fun acquire(context: Context, dim: Boolean) {
        if (overlay != null) return
        if (!canDrawOverlays(context)) {
            Logger.w("ScreenAwake", "Cannot hold screen: overlay permission not granted")
            return
        }
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (dim) screenBrightness = DIM_BRIGHTNESS
        }
        val view = View(context)
        try {
            wm.addView(view, params)
            overlay = view
            Logger.i("ScreenAwake", "Holding screen on", mapOf("dim" to dim.toString()))
        } catch (t: Throwable) {
            Logger.e("ScreenAwake", "Failed to add overlay", t = t)
        }
    }

    private fun release(context: Context) {
        val view = overlay ?: return
        overlay = null
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        try {
            wm.removeView(view)
            Logger.i("ScreenAwake", "Released screen")
        } catch (t: Throwable) {
            Logger.w("ScreenAwake", "Failed to remove overlay", t = t)
        }
    }
}
