package com.jasonschoenbrun.ytmtrigger.playback

import android.app.KeyguardManager
import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Whether a secure lock screen will stop an app-driven entry from starting.
 *
 * Podcasts are unaffected: this app plays them itself, with no activity and no
 * UI, so the keyguard never comes into it. Anything played by another app is
 * different. Starting YouTube Music means opening its activity and pressing
 * Play through the accessibility service, and neither can happen behind a
 * secure keyguard - Android refuses to let an app dismiss one, and the lock
 * screen re-asserts itself over YouTube Music the moment it launches. Measured
 * on the device: three launch attempts, "Aborting: systemui bouncer is
 * foreground" each time, and no audio at all.
 *
 * A swipe lock is fine. It shows a keyguard but holds no credential, so
 * requestDismissKeyguard clears it and everything works.
 *
 * This is a platform boundary rather than something to retry harder at, so the
 * point of checking is to say so plainly in the log and in the failure
 * notification, instead of leaving three failed attempts to be puzzled over.
 */
object LockScreenGuard {

    /** True when a credential is set and the device is locked right now. */
    fun blocksAppLaunch(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return false
        return km.isKeyguardSecure && km.isDeviceLocked
    }

    /** One line for a log or a notification, or null when nothing is in the way. */
    fun describe(context: Context): String? {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return null
        if (!km.isKeyguardSecure) return null
        if (!km.isDeviceLocked) return null
        return "the phone is locked with a PIN, pattern or password. Android will not let " +
            "an app open another app's screen behind a secure lock, so YouTube Music cannot " +
            "be started. Podcasts are unaffected. A swipe-only lock works fully."
    }

    /** Logs the state once per trigger so a failure can be read back later. */
    fun log(context: Context, scheduleId: String) {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return
        Logger.i("LockGuard", "Lock state", mapOf(
            "scheduleId" to scheduleId,
            "secure" to km.isKeyguardSecure.toString(),
            "deviceLocked" to km.isDeviceLocked.toString(),
            "keyguardShowing" to km.isKeyguardLocked.toString(),
        ))
    }
}
