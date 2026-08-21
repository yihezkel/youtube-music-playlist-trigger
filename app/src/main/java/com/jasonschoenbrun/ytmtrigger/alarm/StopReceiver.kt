package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackStopper

/**
 * Fires at a schedule's stop time and pauses playback.
 *
 * Deliberately unconditional: it does not check whether this app started the
 * music. The stop time means "no music after this", and refusing to pause
 * because we cannot prove we caused the playback would be the less useful
 * reading. Pausing when nothing is playing is a harmless no-op.
 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID)
        val name = scheduleId?.let { ScheduleRepository.get(context).byId(it)?.name }
        Logger.i("StopReceiver", "Fired", mapOf(
            "id" to (scheduleId ?: "-"),
            "name" to (name ?: "-"),
        ))
        val stopped = PlaybackStopper.stop(context, reason = "stop time: ${name ?: scheduleId}")
        Logger.i("StopReceiver", "Stop dispatched", mapOf("ok" to stopped.toString()))
    }
}
