package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackTriggerService

class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID) ?: return
        val occurrenceMs = intent.getLongExtra(AlarmScheduler.EXTRA_OCCURRENCE_MS, 0L)
        val manual = intent.getBooleanExtra(AlarmScheduler.EXTRA_MANUAL, false)
        Logger.i("TriggerReceiver", "Fired", mapOf(
            "id" to scheduleId,
            "manual" to manual.toString(),
            "occurrenceMs" to occurrenceMs.toString(),
        ))

        // Hold a brief wakelock so re-arm + service start succeed even if device is dozing.
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTMT:trigger")
        wl?.acquire(15_000)
        try {
            val pendingResult = goAsync()
            try {
                // Re-arm next occurrence (skip if manual)
                if (!manual) {
                    val repo = ScheduleRepository.get(context)
                    repo.byId(scheduleId)?.let { AlarmScheduler.scheduleNext(context, it) }
                }
                // Start FGS to handle wake/launch/verify/post-actions
                val svc = Intent(context, PlaybackTriggerService::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(AlarmScheduler.EXTRA_MANUAL, manual)
                    // Forwarded so a confirmed override survives the hop
                    // through the alarm; absent means "not confirmed".
                    putExtra(
                        AlarmScheduler.EXTRA_OVERRIDE_CALENDAR,
                        intent.getBooleanExtra(AlarmScheduler.EXTRA_OVERRIDE_CALENDAR, false),
                    )
                }
                context.startForegroundService(svc)
                Logger.i("TriggerReceiver", "Started PlaybackTriggerService", mapOf("id" to scheduleId))
            } finally {
                pendingResult.finish()
            }
        } finally {
            try { wl?.release() } catch (_: Throwable) {}
        }
    }
}
