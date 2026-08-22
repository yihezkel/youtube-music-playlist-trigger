package com.jasonschoenbrun.ytmtrigger.playback

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.screen.ScreenAwake

/**
 * Notification listener whose original purpose was to give us
 * NOTIFICATION_LISTENER permission, which is the gate for
 * [android.media.session.MediaSessionManager] to enumerate active media
 * sessions.
 *
 * It now also watches those sessions, which is what lets the app hold the
 * screen awake exactly while music plays instead of leaving the developer
 * "Stay awake" option on permanently. Watching costs nothing extra here: the
 * system keeps this service bound anyway, so there is no polling and no
 * additional permission.
 *
 * F-fix-1 (default-on, eval-traced).
 */
class MediaSessionListenerService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var watched: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            onStateChanged(state?.state)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            logMetadata(metadata)
        }

        override fun onSessionDestroyed() {
            onStateChanged(null)
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(controllers.orEmpty())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Logger.i("MediaListener", "Listener connected")
        val mgr = getSystemService(MediaSessionManager::class.java) ?: return
        val self = ComponentName(this, MediaSessionListenerService::class.java)
        try {
            mgr.addOnActiveSessionsChangedListener(sessionsListener, self, main)
            attach(mgr.getActiveSessions(self))
        } catch (t: Throwable) {
            Logger.w("MediaListener", "Could not observe sessions", t = t)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        detach()
        main.post { ScreenAwake.apply(applicationContext, keepOn = false, dim = false) }
        runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        Logger.w("MediaListener", "Listener disconnected")
    }

    /** Follow YT Music's session, if it is among the active ones. */
    private fun attach(controllers: List<MediaController>) {
        val ytm = controllers.firstOrNull { it.packageName == YT_MUSIC_PKG }
        if (ytm != null && ytm.sessionToken == watched?.sessionToken) {
            onStateChanged(ytm.playbackState?.state)
            return
        }
        detach()
        if (ytm == null) { onStateChanged(null); return }
        watched = ytm
        ytm.registerCallback(controllerCallback, main)
        onStateChanged(ytm.playbackState?.state)
        logMetadata(ytm.metadata)
    }

    private fun detach() {
        watched?.let { runCatching { it.unregisterCallback(controllerCallback) } }
        watched = null
    }

    private fun onStateChanged(state: Int?) {
        val playing = state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING
        val settings = runCatching { SettingsRepository.get(this).current() }.getOrNull()
        val wanted = playing && (settings?.keepScreenOnWhilePlaying ?: false)
        val dim = settings?.dimWhileKeepingScreenOn ?: true
        main.post {
            val was = ScreenAwake.isHeld()
            ScreenAwake.apply(applicationContext, wanted, dim)
            if (was != ScreenAwake.isHeld()) {
                Logger.i("ScreenAwake", "Screen hold changed", mapOf(
                    "playing" to playing.toString(),
                    "held" to ScreenAwake.isHeld().toString(),
                ))
            }
        }
    }

    /**
     * Record what YouTube Music reports about the current track.
     *
     * This exists to answer an open question: whether an upload can be told
     * apart from ordinary catalogue content *before* it starts, which would
     * let the screen be held only when it is actually needed. Nothing acts on
     * it yet - it is evidence being gathered, not a feature.
     */
    private fun logMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        fun s(key: String) = metadata.getString(key)?.take(60)
        Logger.d("MediaMeta", "Track metadata", mapOf(
            "title" to (s(MediaMetadata.METADATA_KEY_TITLE) ?: "-"),
            "artist" to (s(MediaMetadata.METADATA_KEY_ARTIST) ?: "-"),
            "album" to (s(MediaMetadata.METADATA_KEY_ALBUM) ?: "-"),
            "albumArtist" to (s(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "-"),
            "mediaId" to (s(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: "-"),
            "mediaUri" to (s(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: "-"),
            "displaySubtitle" to (s(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "-"),
            "displayDescription" to (s(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION) ?: "-"),
            "durationMs" to metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).toString(),
            "keys" to metadata.keySet().sorted().joinToString(","),
        ))
    }

    // Notification posted/removed events are not needed; the permission this
    // service carries is what matters.
    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* no-op */ }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op */ }

    companion object {
        const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"

        @Volatile private var connected: Boolean = false

        fun isListenerConnected(): Boolean = connected
    }
}
