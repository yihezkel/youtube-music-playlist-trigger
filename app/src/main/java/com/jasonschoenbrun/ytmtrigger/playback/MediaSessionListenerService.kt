package com.jasonschoenbrun.ytmtrigger.playback

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Notification listener whose only purpose is to give us NOTIFICATION_LISTENER
 * permission, which is the gate for [android.media.session.MediaSessionManager]
 * to enumerate active media sessions.
 *
 * Once the user enables this in Settings -> Notification access, the static
 * helper [activeYtmPlaybackState] can answer "is YT Music currently playing
 * audio" with high reliability, far better than [android.media.AudioManager]
 * .isMusicActive (which is true for any audio focus holder).
 *
 * F-fix-1 (default-on, eval-traced).
 */
class MediaSessionListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Logger.i("MediaListener", "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        Logger.w("MediaListener", "Listener disconnected")
    }

    // We do not actually need notification posted/removed events; the only
    // reason this service exists is so the system grants us permission to
    // enumerate active MediaSessions.
    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* no-op */ }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op */ }

    companion object {
        const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"

        @Volatile private var connected: Boolean = false

        fun isListenerConnected(): Boolean = connected
    }
}
