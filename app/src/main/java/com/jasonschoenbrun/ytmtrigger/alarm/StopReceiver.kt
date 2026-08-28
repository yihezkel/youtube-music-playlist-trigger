package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.data.ScheduleChain
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackStopper
import com.jasonschoenbrun.ytmtrigger.playback.MusicEndWatcher
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackTriggerService

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
        val repo = ScheduleRepository.get(context)
        val name = scheduleId?.let { repo.byId(it)?.name }
        Logger.i("StopReceiver", "Fired", mapOf(
            "id" to (scheduleId ?: "-"),
            "name" to (name ?: "-"),
        ))
        val stopped = PlaybackStopper.stop(context, reason = "stop time: ${name ?: scheduleId}")
        Logger.i("StopReceiver", "Stop dispatched", mapOf("ok" to stopped.toString()))
        if (scheduleId != null) AutoStop.clear(context, scheduleId)
        // The block is over; a pending music check must not resurrect it.
        if (scheduleId != null) MusicEndWatcher.cancel(context, scheduleId)

        // Hand on to whatever follows this block. A block that ends in music
        // never reports a queue end - YouTube Music is playing it, not us - so
        // for those this stop is the only signal that the block is over.
        // A block that ends with its queue has no stop alarm at all, so the two
        // paths cannot both fire for the same block.
        val next = scheduleId?.let { id -> ScheduleChain.next(repo.all(), id) }
        if (next != null) {
            Logger.i("StopReceiver", "Starting the block that follows", mapOf(
                "after" to (scheduleId ?: "-"), "next" to next.id, "name" to next.name,
            ))
            PlaybackTriggerService.startQueued(context, next.id, 0)
        }
    }
}
