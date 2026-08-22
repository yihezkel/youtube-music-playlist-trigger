package com.jasonschoenbrun.ytmtrigger.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Stops YouTube Music playback.
 *
 * Two mechanisms, tried in order:
 *
 *  1. [MediaController.TransportControls.pause] — needs notification-listener
 *     access so we can list active media sessions. Most reliable when granted,
 *     and it targets YT Music specifically.
 *  2. [AudioManager.dispatchMediaKeyEvent] with [KeyEvent.KEYCODE_MEDIA_PAUSE].
 *     Needs no permission, but routes to whichever app owns audio focus, so it
 *     is only correct when YT Music is the thing playing.
 *
 * Shared by the self-test, the per-schedule stop time, and the console's stop
 * command so all three behave identically.
 */
object PlaybackStopper {

    /** @return true if a pause was dispatched by either mechanism. */
    fun stop(context: Context, reason: String): Boolean {
        // Our own podcast player first: it is the one thing we control
        // directly, and it publishes a session the loop below would otherwise
        // have to find among everything else playing.
        com.jasonschoenbrun.ytmtrigger.podcast.PodcastPlayerService.stop(context)
        if (tryMediaController(context, reason)) return true
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Logger.i("Stop", "Pause dispatched via KEYCODE_MEDIA_PAUSE", mapOf("reason" to reason))
            true
        } catch (t: Throwable) {
            Logger.w("Stop", "Pause dispatch failed", mapOf("reason" to reason), t = t)
            false
        }
    }

    private fun tryMediaController(context: Context, reason: String): Boolean {
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return false
        val listenerComp = ComponentName(context, MediaSessionListenerService::class.java)
        val sessions: List<MediaController> = try {
            mgr.getActiveSessions(listenerComp)
        } catch (t: Throwable) {
            // SecurityException when notif listener isn't granted — fall back.
            return false
        }
        val ytm = sessions.firstOrNull { it.packageName == MediaSessionListenerService.YT_MUSIC_PKG }
            ?: return false
        val state = ytm.playbackState?.state
        val pausable = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
        if (!pausable) return false
        return try {
            ytm.transportControls.pause()
            Logger.i("Stop", "Pause sent via MediaController", mapOf("reason" to reason))
            true
        } catch (t: Throwable) {
            Logger.w("Stop", "MediaController pause failed", mapOf("reason" to reason), t = t)
            false
        }
    }
}
