package com.jasonschoenbrun.ytmtrigger.playback

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Wrapper around [MediaSessionManager.getActiveSessions] that requires the
 * NOTIFICATION_LISTENER permission. Used to answer "is YT Music actually
 * playing right now" with high reliability (F-fix-1).
 */
object MediaSessionProbe {

    /** Possible outcomes when asking "is YT Music playing right now?". */
    sealed class Status {
        /** YT Music has an active session in STATE_PLAYING. */
        data object Playing : Status()
        /** YT Music has an active session but not currently STATE_PLAYING. */
        data class NotPlaying(val state: Int, val stateName: String) : Status()
        /** YT Music does not have an active media session. */
        data object NoSession : Status()
        /** Could not query MediaSessionManager (permission missing or other). */
        data class Unavailable(val reason: String) : Status()
    }

    fun status(context: Context, pkg: String = MediaSessionListenerService.YT_MUSIC_PKG): Status {
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return Status.Unavailable("no MediaSessionManager")
        val listenerComp = ComponentName(context, MediaSessionListenerService::class.java)
        val sessions: List<MediaController> = try {
            mgr.getActiveSessions(listenerComp)
        } catch (se: SecurityException) {
            return Status.Unavailable("SecurityException: ${se.message}")
        } catch (t: Throwable) {
            return Status.Unavailable("${t.javaClass.simpleName}: ${t.message}")
        }
        // An app publishes more than one session - a local player and a cast
        // controller - and the first is not reliably the one playing. Taking the
        // first would report NOT_PLAYING while audio was audible, which for the
        // end-of-playback watch would cut a block short.
        val mine = sessions.filter { it.packageName == pkg }
        if (mine.isEmpty()) return Status.NoSession
        val chosen = mine.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: mine.first()
        val state = chosen.playbackState
        if (state == null) return Status.NotPlaying(PlaybackState.STATE_NONE, "STATE_NONE(null)")
        return if (state.state == PlaybackState.STATE_PLAYING) {
            Status.Playing
        } else {
            Status.NotPlaying(state.state, stateName(state.state))
        }
    }

    /** Convenience for the YouTube Music path, which is most of the callers. */
    fun ytMusicStatus(context: Context): Status = status(context, MediaSessionListenerService.YT_MUSIC_PKG)

    /** Logs an [EvalFix] entry comparing what AudioManager.isMusicActive says
     *  vs what the MediaSession says. Used by [PlaybackTriggerService] so we
     *  can decide in v0.3 whether to keep relying on AudioManager. */
    fun logComparison(context: Context, audioManagerSaysActive: Boolean) {
        val status = ytMusicStatus(context)
        EvalFix.once("F-fix-1-mediaSession", success = true, fields = mapOf(
            "audioManager" to audioManagerSaysActive.toString(),
            "mediaSession" to statusToShortName(status),
            "agree" to (audioManagerSaysActive == (status is Status.Playing)).toString(),
        ))
        if (status is Status.Unavailable) {
            Logger.w("MediaSession", "Probe unavailable", mapOf("reason" to status.reason))
        }
    }

    private fun statusToShortName(s: Status): String = when (s) {
        is Status.Playing -> "PLAYING"
        is Status.NotPlaying -> "NOT_PLAYING(${s.stateName})"
        is Status.NoSession -> "NO_SESSION"
        is Status.Unavailable -> "UNAVAILABLE"
    }

    private fun stateName(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "STATE_NONE"
        PlaybackState.STATE_STOPPED -> "STATE_STOPPED"
        PlaybackState.STATE_PAUSED -> "STATE_PAUSED"
        PlaybackState.STATE_PLAYING -> "STATE_PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "STATE_FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "STATE_REWINDING"
        PlaybackState.STATE_BUFFERING -> "STATE_BUFFERING"
        PlaybackState.STATE_ERROR -> "STATE_ERROR"
        PlaybackState.STATE_CONNECTING -> "STATE_CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "STATE_SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "STATE_SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "STATE_SKIPPING_TO_QUEUE_ITEM"
        else -> "STATE_UNKNOWN($state)"
    }
}
