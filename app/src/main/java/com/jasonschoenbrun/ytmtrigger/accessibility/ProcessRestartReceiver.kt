package com.jasonschoenbrun.ytmtrigger.accessibility

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Wakes the app process back up after [A11yPermissionEnforcer] deliberately
 * kills it to clear an unresponsive accessibility binding.
 *
 * Delivery of the broadcast is the whole point: starting the receiver starts
 * the process, which runs `YtmApp.onCreate` and re-checks service health.
 * AccessibilityManagerService usually rebinds a killed service on its own,
 * but that is not contractual, and this app's entire purpose depends on the
 * service being alive — so the wake-up is armed as a guarantee rather than
 * trusted to happen.
 */
class ProcessRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.i("A11yPerm", "Process restarted after accessibility recovery")
    }

    companion object {
        const val ACTION = "com.jasonschoenbrun.ytmtrigger.RESTART_PROCESS"

        /** Arm a one-shot wake-up [delayMs] from now. */
        fun arm(context: Context, delayMs: Long = 4_000L) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                /* requestCode = */ 0xA11E,
                Intent(context, ProcessRestartReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val at = System.currentTimeMillis() + delayMs
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Logger.i("A11yPerm", "Armed process restart", mapOf("inMs" to delayMs.toString()))
            } catch (se: SecurityException) {
                Logger.e("A11yPerm", "Could not arm process restart", t = se)
            }
        }
    }
}
