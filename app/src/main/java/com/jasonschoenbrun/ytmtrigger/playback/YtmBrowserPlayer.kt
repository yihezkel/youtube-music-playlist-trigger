package com.jasonschoenbrun.ytmtrigger.playback

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Starts YouTube Music without opening its screen.
 *
 * The screen is the problem. Our normal path opens YouTube Music's activity and
 * presses Play through the accessibility service, and a secure lock forbids
 * both: Android will not let an app dismiss a PIN keyguard, and the lock screen
 * takes the foreground back the moment YouTube Music launches. Measured on the
 * device, that is three failed attempts and no audio.
 *
 * MediaBrowserService is the way round it, and it is YouTube Music's own front
 * door rather than a trick. The app declares
 * `.mediabrowser.MusicBrowserService`, which is the documented interface a car
 * head unit, Android Auto or a watch uses to browse and play someone's library.
 * It is all service calls: no activity, no window, nothing for a keyguard to
 * refuse. The account, the entitlement and the playback all stay inside
 * YouTube Music exactly as before - we are asking it to play, not playing for it.
 */
object YtmBrowserPlayer {

    private const val PKG = MediaSessionListenerService.YT_MUSIC_PKG
    private const val SERVICE = "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService"
    private const val CONNECT_TIMEOUT_MS = 15_000L

    /** Everything a caller needs, or null when the service would not connect. */
    class Session(
        val browser: MediaBrowser,
        val controller: MediaController,
    ) {
        fun release() = runCatching { browser.disconnect() }
    }

    /** Connect to YouTube Music's browser service. Must be called on the main thread. */
    suspend fun connect(context: Context): Session? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var settled = false
        lateinit var browser: MediaBrowser

        val finish: (Session?) -> Unit = { s ->
            if (!settled) { settled = true; cont.resume(s) }
        }
        val timeout = Runnable {
            if (!settled) {
                Logger.w("YtmBrowser", "Connect timed out", mapOf("afterMs" to CONNECT_TIMEOUT_MS.toString()))
                runCatching { browser.disconnect() }
                finish(null)
            }
        }

        val callbacks = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                handler.removeCallbacks(timeout)
                val token = browser.sessionToken
                val controller = MediaController(context, token)
                Logger.i("YtmBrowser", "Connected", mapOf(
                    "root" to browser.root,
                    "actions" to (controller.playbackState?.actions?.toString() ?: "-"),
                ))
                finish(Session(browser, controller))
            }
            override fun onConnectionSuspended() {
                Logger.w("YtmBrowser", "Connection suspended")
                handler.removeCallbacks(timeout)
                finish(null)
            }
            override fun onConnectionFailed() {
                Logger.w("YtmBrowser", "Connection failed - YouTube Music refused the browser client")
                handler.removeCallbacks(timeout)
                finish(null)
            }
        }

        browser = MediaBrowser(context, ComponentName(PKG, SERVICE), callbacks, null)
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        runCatching { browser.connect() }.onFailure {
            handler.removeCallbacks(timeout)
            Logger.w("YtmBrowser", "connect() threw", t = it)
            finish(null)
        }
        cont.invokeOnCancellation { runCatching { browser.disconnect() } }
    }

    /**
     * Ask YouTube Music to play [query], by name.
     *
     * This is what "play X" on a car head unit does. A playlist is named rather
     * than addressed by id, so the name in the schedule has to match the name in
     * the library - which is why the caller verifies rather than assumes.
     */
    fun playFromSearch(session: Session, query: String): Boolean = runCatching {
        session.controller.transportControls.playFromSearch(query, null)
        Logger.i("YtmBrowser", "playFromSearch sent", mapOf("query" to query))
        true
    }.getOrElse {
        Logger.w("YtmBrowser", "playFromSearch failed", mapOf("query" to query), t = it)
        false
    }

    /** Play a specific item from the browse tree. */
    fun playFromMediaId(session: Session, mediaId: String): Boolean = runCatching {
        session.controller.transportControls.playFromMediaId(mediaId, null)
        Logger.i("YtmBrowser", "playFromMediaId sent", mapOf("mediaId" to mediaId.take(60)))
        true
    }.getOrElse {
        Logger.w("YtmBrowser", "playFromMediaId failed", t = it)
        false
    }

    /**
     * List one level of the browse tree.
     *
     * Used to find a playlist's real media id, and to record what YouTube Music
     * actually offers a headless client - which is worth logging once rather
     * than guessing at.
     */
    suspend fun children(session: Session, parentId: String): List<MediaBrowser.MediaItem> =
        suspendCancellableCoroutine { cont ->
            var settled = false
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable { if (!settled) { settled = true; cont.resume(emptyList()) } }
            handler.postDelayed(timeout, 10_000)
            session.browser.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(pid: String, children: MutableList<MediaBrowser.MediaItem>) {
                    handler.removeCallbacks(timeout)
                    if (!settled) { settled = true; cont.resume(children.toList()) }
                }
                override fun onError(pid: String) {
                    handler.removeCallbacks(timeout)
                    Logger.w("YtmBrowser", "Browse failed", mapOf("parent" to pid))
                    if (!settled) { settled = true; cont.resume(emptyList()) }
                }
            })
        }
}
