package com.jasonschoenbrun.ytmtrigger.accessibility

import android.content.Context
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jasonschoenbrun.ytmtrigger.log.Logger
import java.util.concurrent.TimeUnit

/**
 * Periodically records whether the accessibility service is still alive.
 *
 * ## Why this exists
 * Four self-tests out of 58 over ten days failed with the service bound but
 * delivering nothing. The association is strong — all four were the first run
 * after a process start, and the two unattended ones came 190 and 358 minutes
 * after that start, while no successful run in that group exceeded 30 minutes
 * — but the mechanism is still unproven. Two candidate explanations were
 * tested and rejected: a 25-second forced freeze did not reproduce it, and
 * screen-off does not explain it, since twelve screen-off runs passed.
 *
 * The gap is that health was only ever sampled when a self-test or trigger
 * ran, i.e. every six hours, so the moment a binding goes quiet was never
 * observed. Sampling every 15 minutes turns an intermittent 7% fault into a
 * timeline: how long a binding survives, and what the device was doing when
 * it stopped.
 *
 * This is instrumentation, not a fix. It is deliberately cheap — one binder
 * call and one log line — and is expected to be removed or reduced once the
 * mechanism is understood.
 */
class A11yHealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            val bound = YtmAccessibilityService.isRunning()
            // Blocking binder call, so it must not run on the main thread.
            // CoroutineWorker dispatches to a background thread.
            val state = A11yPermissionEnforcer.liveness(applicationContext)
            val readable = state == A11yPermissionEnforcer.Liveness.Healthy
            val pm = applicationContext.getSystemService(PowerManager::class.java)
            Logger.i("A11yHealth", "sample", mapOf(
                "bound" to bound.toString(),
                "canReadWindow" to readable.toString(),
                "liveness" to state.name,
                "sawEventSinceConnect" to YtmAccessibilityService.isResponsive().toString(),
                "connectedMinAgo" to minutes(YtmAccessibilityService.msSinceConnected()),
                "lastEventMinAgo" to minutes(YtmAccessibilityService.msSinceLastEvent()),
                "interactive" to (pm?.isInteractive?.toString() ?: "?"),
                "deviceIdle" to (pm?.isDeviceIdleMode?.toString() ?: "?"),
            ))
            if (state == A11yPermissionEnforcer.Liveness.Unresponsive) {
                // The signature of the fault, captured at the moment it starts
                // rather than six hours later when a self-test trips over it.
                // Only warn when the screen was on: with it off there is no
                // active window to read, so an unreadable one proves nothing.
                Logger.w("A11yHealth", "Accessibility bound but not readable", mapOf(
                    "connectedMinAgo" to minutes(YtmAccessibilityService.msSinceConnected()),
                    "lastEventMinAgo" to minutes(YtmAccessibilityService.msSinceLastEvent()),
                ))
            }
        } catch (t: Throwable) {
            Logger.w("A11yHealth", "sample failed", t = t)
        }
        return Result.success()
    }

    private fun minutes(ms: Long?): String =
        if (ms == null) "never" else (ms / 60000).toString()

    companion object {
        private const val UNIQUE_NAME = "a11y-health"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<A11yHealthWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
