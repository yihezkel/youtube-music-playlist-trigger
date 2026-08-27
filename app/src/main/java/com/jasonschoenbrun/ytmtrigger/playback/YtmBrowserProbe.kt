package com.jasonschoenbrun.ytmtrigger.playback

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds out what YouTube Music will let a headless client do.
 *
 * Everything here is a question rather than an assumption: whether the browser
 * service accepts us at all, what it offers, and whether asking it to play
 * actually produces audio. It logs what it finds so the answer is on the record
 * instead of in someone's memory.
 */
object YtmBrowserProbe {

    /**
     * What YouTube Music's own media session will accept.
     *
     * A session is not allowlisted the way the browser service is - any app
     * holding notification-listener access can read it and send transport
     * controls - and transport controls are service calls, so no window is
     * opened and a keyguard has nothing to refuse. If playFromSearch works here
     * it is the whole answer to the lock problem.
     */
    private suspend fun probeSessionControls(context: Context, query: String?) {
        val pkg = MediaSessionListenerService.YT_MUSIC_PKG
        val controller = MediaAppController.session(context, pkg)
        if (controller == null) {
            Logger.w("YtmProbe", "No YouTube Music media session to talk to; open it once first")
            return
        }
        val actions = controller.playbackState?.actions ?: 0L
        fun has(bit: Long, name: String) =
            Logger.i("YtmProbe", "  advertises $name", mapOf("yes" to (actions and bit != 0L).toString()))
        Logger.i("YtmProbe", "Session found", mapOf(
            "state" to (controller.playbackState?.state?.toString() ?: "-"),
            "actions" to actions.toString(),
        ))
        has(android.media.session.PlaybackState.ACTION_PLAY, "PLAY")
        has(android.media.session.PlaybackState.ACTION_PLAY_FROM_SEARCH, "PLAY_FROM_SEARCH")
        has(android.media.session.PlaybackState.ACTION_PLAY_FROM_MEDIA_ID, "PLAY_FROM_MEDIA_ID")
        has(android.media.session.PlaybackState.ACTION_PREPARE_FROM_SEARCH, "PREPARE_FROM_SEARCH")

        if (query.isNullOrBlank()) return
        val locked = LockScreenGuard.blocksAppLaunch(context)
        MediaAppController.playFromSearch(context, pkg, query)
        var playing = false
        repeat(20) {
            delay(1000)
            if (MediaAppController.isPlaying(context, pkg)) { playing = true; return@repeat }
        }
        Logger.i("YtmProbe", "SESSION playFromSearch outcome", mapOf(
            "query" to query,
            "playingNow" to playing.toString(),
            "lockedWhileTrying" to locked.toString(),
            "nowPlaying" to (MediaAppController.nowPlaying(context, pkg) ?: "-"),
        ))
    }

    fun run(context: Context, query: String?) {
        CoroutineScope(Dispatchers.Main).launch {
            val km = LockScreenGuard.describe(context)
            Logger.i("YtmProbe", "Starting", mapOf(
                "query" to (query ?: "-"),
                "lock" to (if (km == null) "not blocking" else "SECURE LOCK ENGAGED"),
            ))
            val session = YtmBrowserPlayer.connect(context)
            if (session == null) {
                Logger.e("YtmProbe", "Could not connect to the browser service")
                // The browser service is allowlisted to Android Auto, Wear and
                // car head units, so refusal is expected rather than a fault.
                // Its own media session is a separate door and needs no
                // allowlist: try the transport controls directly.
                probeSessionControls(context, query)
                return@launch
            }
            try {
                // What does it offer a client it has never seen before?
                val root = session.browser.root
                val top = YtmBrowserPlayer.children(session, root)
                Logger.i("YtmProbe", "Root children", mapOf(
                    "root" to root, "count" to top.size.toString(),
                ))
                for (item in top.take(20)) {
                    Logger.i("YtmProbe", "  item", mapOf(
                        "title" to (item.description.title?.toString() ?: "-"),
                        "id" to (item.mediaId ?: "-").take(70),
                        "browsable" to item.isBrowsable.toString(),
                        "playable" to item.isPlayable.toString(),
                    ))
                }
                // One level down, where a library's playlists usually live.
                val firstBrowsable = top.firstOrNull { it.isBrowsable }
                if (firstBrowsable?.mediaId != null) {
                    val kids = YtmBrowserPlayer.children(session, firstBrowsable.mediaId!!)
                    Logger.i("YtmProbe", "Children of '${firstBrowsable.description.title}'", mapOf(
                        "count" to kids.size.toString(),
                    ))
                    for (k in kids.take(20)) {
                        Logger.i("YtmProbe", "    child", mapOf(
                            "title" to (k.description.title?.toString() ?: "-"),
                            "id" to (k.mediaId ?: "-").take(70),
                            "playable" to k.isPlayable.toString(),
                        ))
                    }
                }
                // The question that matters: can it be made to play?
                if (!query.isNullOrBlank()) {
                    val before = MediaAppController.isPlaying(context, MediaSessionListenerService.YT_MUSIC_PKG)
                    YtmBrowserPlayer.playFromSearch(session, query)
                    var playing = false
                    repeat(20) {
                        delay(1000)
                        if (MediaAppController.isPlaying(context, MediaSessionListenerService.YT_MUSIC_PKG)) {
                            playing = true
                            return@repeat
                        }
                    }
                    Logger.i("YtmProbe", "playFromSearch outcome", mapOf(
                        "query" to query,
                        "wasPlayingBefore" to before.toString(),
                        "playingNow" to playing.toString(),
                        "nowPlaying" to (MediaAppController.nowPlaying(context, MediaSessionListenerService.YT_MUSIC_PKG) ?: "-"),
                        "lock" to (if (LockScreenGuard.blocksAppLaunch(context)) "LOCKED" else "unlocked"),
                    ))
                }
            } finally {
                session.release()
                Logger.i("YtmProbe", "Done")
            }
        }
    }
}
