package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.jasonschoenbrun.ytmtrigger.log.Logger
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
