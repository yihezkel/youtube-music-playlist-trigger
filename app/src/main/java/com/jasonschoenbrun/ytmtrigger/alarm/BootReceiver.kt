package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.selftest.SelfTestScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.i("BootReceiver", "Received", mapOf("action" to (intent.action ?: "?")))
        // On some OEMs Android disables sideloaded A11y services across reboots
        // and across package-replace. Re-assert ours immediately if we hold the
        // WRITE_SECURE_SETTINGS grant.
        try {
            A11yPermissionEnforcer.ensureEnabled(context)
        } catch (t: Throwable) {
            Logger.e("BootReceiver", "A11y re-enable failed", t = t)
        }
        val repo = ScheduleRepository.get(context)
        AlarmScheduler.rescheduleAll(context, repo.all())
        // Self-test scheduler also needs to be re-armed on boot since
        // setAlarmClock alarms do not survive a reboot.
        try {
            val s = SettingsRepository.get(context).current()
            SelfTestScheduler.ensureScheduled(context, s.selfTestEnabled)
        } catch (t: Throwable) {
            Logger.e("BootReceiver", "Self-test re-arm failed", t = t)
        }
    }
}
