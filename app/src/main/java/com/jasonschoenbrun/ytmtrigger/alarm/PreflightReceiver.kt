package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Wakes the app a few minutes before a scheduled trigger and makes sure the
 * accessibility service is actually usable.
 *
 * The failures seen so far all landed on a process that had been idle for
 * hours: the two unattended ones came 190 and 358 minutes after the process
 * started, while no successful self-test in that group had been idle for more
 * than 30 minutes. Repairing at trigger time is too late — the only recovery
 * that works is a process restart, and restarting then would kill the very
 * playback it is meant to protect. Running early, with lead time, means a
 * restart costs nothing and the trigger finds a fresh binding.
 *
 * The trigger alarm itself is owned by AlarmManager and survives the restart.
 */
class PreflightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val appCtx = context.applicationContext
        Logger.i("Preflight", "Fired ahead of scheduled trigger")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val restarting = A11yPermissionEnforcer.preflightRepair(appCtx)
                if (restarting) {
                    // Let the logger flush before the process goes away.
                    delay(700)
                    pending.finish()
                    exitProcess(0)
                }
            } catch (t: Throwable) {
                Logger.e("Preflight", "Preflight repair failed", t = t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION = "com.jasonschoenbrun.ytmtrigger.PREFLIGHT"
    }
}
