package com.jasonschoenbrun.ytmtrigger.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.PlaybackState
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Works out, roughly, when a YouTube Music playlist has finished.
 *
 * We are told exactly when our own podcast episodes end, because we play them.
 * YouTube Music plays its own playlists, so there is no end to be told about -
 * which is why a music entry used to have to be the last thing in a queue, and
 * why a block whose playlist ran out simply went quiet for the rest of its
 * window.
 *
 * Polling closes that gap without pretending to precision we do not have: every
 * [CHECK_INTERVAL_MIN] minutes, ask whether YouTube Music is still playing. When
 * it is not, treat the entry as finished and move the queue on. The cost is that
 * the end is only located to within five minutes.
 *
 * Two things stop it reporting an end that has not happened. States that are
 * merely between tracks - buffering, skipping, connecting - count as playing,
 * so an ordinary track change is not mistaken for the end of the playlist. And
 * the AudioManager is consulted as well, so music still counts as playing when
 * the media session cannot be read at all.
 */
object MusicEndWatcher {

    /** How often to ask. The end of a playlist is located to within this. */
    const val CHECK_INTERVAL_MIN = 5L

    private const val ACTION = "com.jasonschoenbrun.ytmtrigger.MUSIC_CHECK"
    const val EXTRA_SCHEDULE_ID = "scheduleId"
    const val EXTRA_QUEUE_INDEX = "queueIndex"
    const val EXTRA_PACKAGE = "pkg"

    /**
     * Begin watching the entry at [queueIndex] of [scheduleId], played by [pkg].
     *
     * Cancels any previous watch on the same schedule, so re-arming cannot leave
     * two running.
     */
    fun watch(context: Context, scheduleId: String, queueIndex: Int, pkg: String = MediaSessionListenerService.YT_MUSIC_PKG) {
        cancel(context, scheduleId)
        arm(context, scheduleId, queueIndex, pkg)
        Logger.i("MusicWatch", "Watching for the end", mapOf(
            "scheduleId" to scheduleId,
            "queueIndex" to queueIndex.toString(),
            "pkg" to pkg,
            "everyMin" to CHECK_INTERVAL_MIN.toString(),
        ))
    }

    fun cancel(context: Context, scheduleId: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intentFor(context, scheduleId, 0, ""))
    }

    internal fun arm(context: Context, scheduleId: String, queueIndex: Int, pkg: String) {
        val at = System.currentTimeMillis() + CHECK_INTERVAL_MIN * 60_000L
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intentFor(context, scheduleId, queueIndex, pkg))
        } catch (se: SecurityException) {
            Logger.w("MusicWatch", "Could not arm the check", mapOf("scheduleId" to scheduleId), t = se)
        }
    }

    private fun intentFor(context: Context, scheduleId: String, queueIndex: Int, pkg: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "musicwatch:$scheduleId".hashCode(),
            Intent(context, MusicEndReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_QUEUE_INDEX, queueIndex)
                .putExtra(EXTRA_PACKAGE, pkg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Whether [pkg] should still be considered playing.
     *
     * Transitional states count as playing: an app reports buffering or skipping
     * between tracks, and calling that the end of the queue would cut a block
     * short every few minutes.
     */
    fun stillPlaying(context: Context, pkg: String): Pair<Boolean, String> {
        val status = MediaSessionProbe.status(context, pkg)
        val audioActive = context.getSystemService(AudioManager::class.java)?.isMusicActive == true
        val playing = when (status) {
            is MediaSessionProbe.Status.Playing -> true
            is MediaSessionProbe.Status.NotPlaying -> when (status.state) {
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
                -> true
                else -> false
            }
            // No session, or we cannot read sessions at all: fall back to
            // whether anything is coming out of the speaker.
            is MediaSessionProbe.Status.NoSession,
            is MediaSessionProbe.Status.Unavailable,
            -> audioActive
        }
        val detail = when (status) {
            is MediaSessionProbe.Status.Playing -> "PLAYING"
            is MediaSessionProbe.Status.NotPlaying -> status.stateName
            is MediaSessionProbe.Status.NoSession -> "NO_SESSION"
            is MediaSessionProbe.Status.Unavailable -> "UNAVAILABLE(${status.reason})"
        }
        return playing to "$detail, audioActive=$audioActive"
    }
}

/** Fires every [MusicEndWatcher.CHECK_INTERVAL_MIN] minutes while music plays. */
class MusicEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getStringExtra(MusicEndWatcher.EXTRA_SCHEDULE_ID) ?: return
        val queueIndex = intent.getIntExtra(MusicEndWatcher.EXTRA_QUEUE_INDEX, 0)
        val pkg = intent.getStringExtra(MusicEndWatcher.EXTRA_PACKAGE)
            ?: MediaSessionListenerService.YT_MUSIC_PKG
        val schedule = ScheduleRepository.get(context).byId(scheduleId)
        if (schedule == null || !schedule.enabled || !schedule.continuousPlay) {
            Logger.i("MusicWatch", "Stopping: schedule is gone or not a queue", mapOf("scheduleId" to scheduleId))
            return
        }
        val (playing, detail) = MusicEndWatcher.stillPlaying(context, pkg)
        Logger.i("MusicWatch", "Checked", mapOf(
            "scheduleId" to scheduleId,
            "queueIndex" to queueIndex.toString(),
            "pkg" to pkg,
            "playing" to playing.toString(),
            "state" to detail,
        ))
        // A block the user paused is not a block that has finished. Paused and
        // "the playlist ran out" look identical from here - both stop being
        // PLAYING - so without this check pausing music would silently skip to
        // the next entry within five minutes, which is the opposite of what
        // pause is for. Keep watching so the end is still noticed after resume.
        if (PlaybackPauser.isPaused()) {
            Logger.i("MusicWatch", "Paused by the user; holding the queue", mapOf(
                "scheduleId" to scheduleId,
            ))
            MusicEndWatcher.arm(context, scheduleId, queueIndex, pkg)
            return
        }
        if (playing) {
            MusicEndWatcher.arm(context, scheduleId, queueIndex, pkg)
            return
        }
        Logger.i("MusicWatch", "Playback appears to have ended; moving the queue on", mapOf(
            "scheduleId" to scheduleId,
            "nextIndex" to (queueIndex + 1).toString(),
        ))
        PlaybackTriggerService.startQueued(context, scheduleId, queueIndex + 1)
    }
}
