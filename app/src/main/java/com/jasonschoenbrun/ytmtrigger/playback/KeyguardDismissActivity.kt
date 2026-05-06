package com.jasonschoenbrun.ytmtrigger.playback

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Invisible activity that wakes the screen and dismisses the keyguard, so that
 * subsequent activity launches (e.g. YouTube Music) succeed even if the device
 * was asleep. We finish ourselves immediately after dispatching the launch
 * intent, which is passed via [intent.data].
 */
class KeyguardDismissActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("KeyguardActivity", "onCreate")
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val km = getSystemService(KeyguardManager::class.java)
        // Only request dismiss if the keyguard is actually showing; otherwise the
        // platform invokes onDismissError, which is noisy and misleading.
        if (km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() { Logger.w("KeyguardActivity", "Dismiss error") }
                override fun onDismissSucceeded() { Logger.i("KeyguardActivity", "Dismiss succeeded") }
                override fun onDismissCancelled() { Logger.w("KeyguardActivity", "Dismiss cancelled") }
            })
        } else {
            Logger.d("KeyguardActivity", "Keyguard not locked; skipping dismiss")
        }

        // Forward a launch intent if provided
        intent?.let {
            val launch = it.getParcelableExtra<android.content.Intent>(EXTRA_LAUNCH)
            if (launch != null) {
                Logger.i("KeyguardActivity", "Forwarding launch intent", mapOf(
                    "data" to (launch.dataString ?: ""),
                    "package" to (launch.`package` ?: ""),
                ))
                runCatching { startActivity(launch) }
                    .onFailure { t -> Logger.e("KeyguardActivity", "Launch failed", t = t) }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_LAUNCH = "launchIntent"
    }
}
