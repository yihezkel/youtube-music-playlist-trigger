package com.jasonschoenbrun.ytmtrigger.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.podcast.PodcastPlayerService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pauses and resumes whatever a block is currently playing.
 *
 * Distinct from [PlaybackStopper], which ends a block: stop releases our
 * podcast player, so the episode is over and only a saved resume mark survives.
 * Pause leaves the player, its position and its place in the queue exactly
 * where they are, so resuming continues the same episode rather than starting
 * something new.
 *
 * It deliberately mirrors [PlaybackStopper]'s shape - our own player first,
 * then other apps through their media sessions, then a media key as a last
 * resort - because a block can be playing through our podcast service, YouTube
 * Music or the Aleph Beta app, and a control on the home screen has to work for
 * all three.
 */
object PlaybackPauser {

    enum class State { Playing, Paused, Idle }

    /** What to show on the home screen: the state, and what it applies to. */
    data class Snapshot(val state: State, val what: String?)

    /**
     * Whether the user paused a block from the app.
     *
     * Needed as an explicit flag rather than read back off the media session,
     * because "paused" and "the playlist finished" look identical from outside
     * and [MusicEndWatcher] has to tell them apart: one must hold the queue
     * still, the other must move it on.
     */
    private val userPaused = AtomicBoolean(false)

    /** Packages paused by [pause], so [resume] restarts those and nothing else. */
    @Volatile private var pausedPackages: List<String> = emptyList()

    /**
     * When the pause was applied, or 0.
     *
     * A pause is silent by design and nothing clears it but a resume, a stop or
     * the next trigger. On 29 Aug the motzaei Shabat blocks were paused a minute
     * into each, and the house then had two hours of silence that no check
     * noticed, because every other check was about whether playback *could*
     * start. This is what lets the health screen say how long it has been held.
     */
    @Volatile private var pausedAtMs: Long = 0L

    fun isPaused(): Boolean = userPaused.get()

    /** How long the pause has been in force, or null if nothing is paused. */
    fun pausedForMs(): Long? =
        if (userPaused.get() && pausedAtMs > 0) System.currentTimeMillis() - pausedAtMs else null

    /**
     * Forget any pause. Called when a block is stopped or a new trigger starts,
     * both of which supersede it - without this the home screen would offer to
     * resume something that no longer exists, and the queue would stay frozen.
     */
    fun clear(reason: String) {
        if (userPaused.getAndSet(false)) {
            pausedPackages = emptyList()
            pausedAtMs = 0L
            Logger.i("Pause", "Pause cleared", mapOf("reason" to reason))
        }
    }

    /** @return true if anything was actually paused. */
    fun pause(context: Context, reason: String): Boolean {
        var did = false
        if (PodcastPlayerService.isPlaying()) {
            PodcastPlayerService.pause(context)
            did = true
        }
        val others = pausePlayingSessions(context, reason)
        pausedPackages = others
        if (others.isNotEmpty()) did = true
        if (!did) {
            // Nothing we could name; the media key still reaches whoever owns
            // audio focus. Only worth it if something is actually audible.
            val am = context.getSystemService(AudioManager::class.java)
            if (am?.isMusicActive == true) {
                did = sendKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE, reason)
            }
        }
        if (did) {
            userPaused.set(true)
            pausedAtMs = System.currentTimeMillis()
            Logger.i("Pause", "Paused", mapOf(
                "reason" to reason, "packages" to pausedPackages.joinToString(","),
            ))
        } else {
            Logger.i("Pause", "Nothing to pause", mapOf("reason" to reason))
        }
        return did
    }

    /** @return true if anything was actually resumed. */
    fun resume(context: Context, reason: String): Boolean {
        var did = false
        if (PodcastPlayerService.isPaused()) {
            PodcastPlayerService.resume(context)
            did = true
        }
        val wanted = pausedPackages
        if (wanted.isNotEmpty()) {
            for (c in sessions(context, reason)) {
                if (c.packageName !in wanted) continue
                try {
                    c.transportControls.play()
                    did = true
                    Logger.i("Pause", "Play sent via MediaController", mapOf(
                        "reason" to reason, "pkg" to c.packageName,
                    ))
                } catch (t: Throwable) {
                    Logger.w("Pause", "MediaController play failed", mapOf(
                        "reason" to reason, "pkg" to c.packageName,
                    ), t = t)
                }
            }
        }
        if (!did) did = sendKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, reason)
        userPaused.set(false)
        pausedPackages = emptyList()
        pausedAtMs = 0L
        Logger.i("Pause", if (did) "Resumed" else "Nothing to resume", mapOf("reason" to reason))
        return did
    }

    /** Current state, for the home screen. Cheap enough to poll. */
    fun snapshot(context: Context): Snapshot {
        if (userPaused.get()) {
            val what = PodcastPlayerService.nowPlaying().ifBlank {
                pausedPackages.firstOrNull()?.let { appLabel(context, it) } ?: ""
            }
            return Snapshot(State.Paused, what.ifBlank { null })
        }
        if (PodcastPlayerService.isPlaying()) {
            return Snapshot(State.Playing, PodcastPlayerService.nowPlaying().ifBlank { null })
        }
        val playing = sessions(context, "snapshot").firstOrNull { c ->
            when (c.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
                else -> false
            }
        }
        if (playing != null) {
            val title = playing.metadata
                ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
            return Snapshot(State.Playing, title ?: appLabel(context, playing.packageName))
        }
        // Something is audible that publishes no readable session.
        val am = context.getSystemService(AudioManager::class.java)
        if (am?.isMusicActive == true) return Snapshot(State.Playing, null)
        return Snapshot(State.Idle, null)
    }

    private fun pausePlayingSessions(context: Context, reason: String): List<String> {
        val paused = mutableListOf<String>()
        for (c in sessions(context, reason)) {
            val playingNow = when (c.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> true
                else -> false
            }
            if (!playingNow) continue
            // Our own player is handled directly above; pausing it again
            // through its session would be harmless but confuses the log.
            if (c.packageName == context.packageName) continue
            try {
                c.transportControls.pause()
                paused += c.packageName
                Logger.i("Pause", "Pause sent via MediaController", mapOf(
                    "reason" to reason, "pkg" to c.packageName,
                ))
            } catch (t: Throwable) {
                Logger.w("Pause", "MediaController pause failed", mapOf(
                    "reason" to reason, "pkg" to c.packageName,
                ), t = t)
            }
        }
        return paused
    }

    private fun sessions(context: Context, reason: String): List<MediaController> {
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return emptyList()
        val listener = ComponentName(context, MediaSessionListenerService::class.java)
        return try {
            mgr.getActiveSessions(listener)
        } catch (t: Throwable) {
            // SecurityException when the notification listener is not granted.
            Logger.w("Pause", "Cannot list media sessions", mapOf(
                "reason" to reason, "err" to t.javaClass.simpleName,
            ))
            emptyList()
        }
    }

    private fun sendKey(context: Context, code: Int, reason: String): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            Logger.i("Pause", "Media key dispatched", mapOf(
                "reason" to reason, "key" to code.toString(),
            ))
            true
        } catch (t: Throwable) {
            Logger.w("Pause", "Media key dispatch failed", mapOf("reason" to reason), t = t)
            false
        }
    }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
