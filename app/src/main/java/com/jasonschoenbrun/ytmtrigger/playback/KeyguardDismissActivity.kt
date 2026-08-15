package com.jasonschoenbrun.ytmtrigger.playback

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Invisible activity that wakes the screen and dismisses the keyguard, so that
 * subsequent activity launches (e.g. YouTube Music) succeed even if the device
 * was asleep. We forward the launch intent passed via [EXTRA_LAUNCH], then
 * delay [FINISH_DELAY_MS] before finishing ourselves (A-fix-1) so the dismiss
 * callback has a chance to fire and the launched activity has a chance to
 * take focus before the keyguard re-asserts.
 */
class KeyguardDismissActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var finishScheduled = false

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
        val wasLocked = km?.isKeyguardLocked == true
        // Only request dismiss if the keyguard is actually showing; otherwise the
        // platform invokes onDismissError, which is noisy and misleading.
        if (wasLocked) {
            EvalFix.start("A-fix-1-delayedFinish", mapOf("wasLocked" to "true"))
            km!!.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() {
                    Logger.w("KeyguardActivity", "Dismiss error")
                    EvalFix.end("A-fix-1-delayedFinish", success = false, mapOf("cb" to "error"))
                    scheduleFinish()
                }
                override fun onDismissSucceeded() {
                    Logger.i("KeyguardActivity", "Dismiss succeeded")
                    EvalFix.end("A-fix-1-delayedFinish", success = true, mapOf("cb" to "succeeded"))
                    scheduleFinish()
                }
                override fun onDismissCancelled() {
                    Logger.w("KeyguardActivity", "Dismiss cancelled")
                    EvalFix.end("A-fix-1-delayedFinish", success = false, mapOf("cb" to "cancelled"))
                    scheduleFinish()
                }
            })
            // Belt-and-braces: schedule the delayed finish even if no callback
            // ever arrives, so we never leak this activity in the foreground.
            scheduleFinish()
        } else {
            Logger.d("KeyguardActivity", "Keyguard not locked; skipping dismiss")
        }

        // Forward a launch intent if provided
        intent?.let {
            val launch = it.getParcelableExtra<Intent>(EXTRA_LAUNCH)
            val launchId = it.getLongExtra(EXTRA_LAUNCH_ID, -1L)
            if (launch != null) {
                // A-fix-5: ensure the launched activity gets its own task and
                // is brought to front cleanly even if YT Music has stale state.
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                Logger.i("KeyguardActivity", "Forwarding launch intent", mapOf(
                    "data" to (launch.dataString ?: ""),
                    "package" to (launch.`package` ?: ""),
                    "flagsHex" to "0x" + Integer.toHexString(launch.flags),
                    "launchId" to launchId.toString(),
                ))
                // This is the real dispatch. Report its outcome so the caller
                // records what actually happened instead of assuming success —
                // an ActivityNotFoundException here used to be logged and then
                // forgotten, leaving run records claiming the launch worked.
                runCatching { startActivity(launch) }
                    .onSuccess {
                        if (launchId >= 0) YtmLauncher.reportResult(launchId, true, null)
                    }
                    .onFailure { t ->
                        Logger.e("KeyguardActivity", "Launch failed", t = t)
                        if (launchId >= 0) {
                            YtmLauncher.reportResult(
                                launchId,
                                false,
                                "${t.javaClass.simpleName}: ${t.message ?: ""}",
                            )
                        }
                    }
            }
        }
        if (!wasLocked) {
            // Nothing to wait for; finish promptly.
            finish()
        }
    }

    private fun scheduleFinish() {
        if (finishScheduled) return
        finishScheduled = true
        handler.postDelayed({
            if (!isFinishing) {
                Logger.d("KeyguardActivity", "Delayed finish firing")
                finish()
            }
        }, FINISH_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LAUNCH = "launchIntent"
        const val EXTRA_LAUNCH_ID = "launchId"
        // 3 seconds: long enough for keyguard dismiss + launched activity to
        // claim focus, short enough that the user doesn't notice an invisible
        // shim activity if the launch fails.
        private const val FINISH_DELAY_MS = 3_000L
    }
}
