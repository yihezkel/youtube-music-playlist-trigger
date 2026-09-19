package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.MusicEndWatcher
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackStopper

/**
 * Fires shortly before Shabat or Yom Tov begins: stops anything playing and
 * mutes the media stream.
 *
 * Stopping alone would not be enough. Playback can be started by hand, or by
 * YouTube Music itself autoplaying on from a queue, and a phone left with the
 * volume up can then make noise after the window has opened. Muting removes
 * that whole class of accident rather than just the instance we can see.
 *
 * The volume is deliberately not restored afterwards: a trigger sets the
 * volume from its schedule before it plays, so the next scheduled run brings
 * it back on its own. The previous level is logged so it can be recovered by
 * hand if a schedule has no volume configured at all.
 */
class ShabatPrepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.getStringExtra(EXTRA_WHAT) ?: "Shabat/Yom Tov"
        val stopped = PlaybackStopper.stop(context, reason = "before $what")

        // End the blocks too, not just the sound coming out of them.
        //
        // A continuous block is kept alive by MusicEndWatcher, which wakes
        // every five minutes and asks whether the current entry has finished.
        // Stopping playback makes the answer "yes", so five minutes after this
        // receiver ran the watcher concluded the entry had ended and started
        // the next one - straight into Shabat. Measured on 18 September 2026:
        //
        //   17:48:24  ShabatPrep: Muted before window, stoppedPlayback=true
        //   17:53:25  MusicWatch: Playback appears to have ended; moving on
        //   17:53:25  Picker: Queue entry index=3 kind=YtmPlaylist Rabbi Machlis
        //
        // It stayed silent only because the volume is set on the first entry
        // of a queue and this was the fourth, so the mute above survived. That
        // is luck, not design: the app went on launching YouTube Music and
        // advancing the queue every few minutes for the whole of Shabat, and
        // anything that restored the volume would have made it audible.
        val schedules = runCatching { ScheduleRepository.get(context).all() }.getOrDefault(emptyList())
        for (s in schedules) MusicEndWatcher.cancel(context, s.id)

        val am = context.getSystemService(AudioManager::class.java)
        val previous = am?.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (t: Throwable) {
            Logger.w("ShabatPrep", "Could not mute media stream", t = t)
        }
        Logger.i("ShabatPrep", "Muted before window", mapOf(
            "what" to what,
            "stoppedPlayback" to stopped.toString(),
            "watchersCancelled" to schedules.size.toString(),
            "previousVolume" to (previous?.toString() ?: "?"),
            "nowVolume" to (am?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toString() ?: "?"),
        ))

        // Re-arm for the window after this one. Measuring from now would find
        // the window we have just prepared for, which is still minutes away,
        // and we would fire again immediately.
        AlarmScheduler.scheduleShabatPrep(
            context,
            fromMs = System.currentTimeMillis() +
                (AlarmScheduler.SHABAT_PREP_LEAD_MIN + 5) * 60_000L,
        )
    }

    companion object {
        const val EXTRA_WHAT = "what"
    }
}
