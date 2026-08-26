package com.jasonschoenbrun.ytmtrigger.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Drives another app's media session.
 *
 * We play podcasts ourselves, so we know exactly what they are doing. Anything
 * played by another app - YouTube Music, Aleph Beta - is opaque: we can launch
 * it and we can watch it, but only through whatever it chooses to publish.
 *
 * A media session is the part it does publish. An app that registers one is
 * offering a documented way to be observed and controlled, and both apps here
 * do: YouTube Music through the platform session, Aleph Beta through
 * androidx.media3.session.MediaSessionService. That is the difference between
 * driving an app and screen-scraping it.
 *
 * Requires notification-listener access, which is how sessions become visible.
 */
object MediaAppController {

    /**
     * The session of [pkg] that is worth talking to.
     *
     * An app can publish several - YouTube Music has a local player and a cast
     * controller - and the first is not reliably the live one. A session that is
     * actually playing wins; otherwise the first is returned so a paused app can
     * still be resumed.
     */
    fun session(context: Context, pkg: String): MediaController? {
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val listener = ComponentName(context, MediaSessionListenerService::class.java)
        val all = try {
            mgr.getActiveSessions(listener)
        } catch (t: Throwable) {
            Logger.w("MediaApp", "Cannot list sessions", mapOf("pkg" to pkg, "err" to t.javaClass.simpleName))
            return null
        }
        val mine = all.filter { it.packageName == pkg }
        if (mine.isEmpty()) return null
        return mine.firstOrNull { isActive(it.playbackState?.state) } ?: mine.first()
    }

    /** True while the app is playing, counting the states that mean "between tracks". */
    fun isPlaying(context: Context, pkg: String): Boolean =
        isActive(session(context, pkg)?.playbackState?.state)

    /** Whether [pkg] advertises [action] right now, per its PlaybackState. */
    fun supports(context: Context, pkg: String, action: Long): Boolean {
        val actions = session(context, pkg)?.playbackState?.actions ?: return false
        return actions and action != 0L
    }

    /**
     * Ask [pkg] to play [query].
     *
     * Aleph Beta advertises PLAY_FROM_SEARCH, which is a documented request to
     * be asked for content by name. Where it works this replaces tapping a Play
     * button by screen coordinate, which breaks whenever a layout moves.
     *
     * @return true if the request was delivered - not that playback started,
     *   which the caller must confirm for itself.
     */
    fun playFromSearch(context: Context, pkg: String, query: String): Boolean {
        val controls = session(context, pkg)?.transportControls ?: run {
            Logger.w("MediaApp", "No session to search in", mapOf("pkg" to pkg))
            return false
        }
        return try {
            controls.playFromSearch(query, null)
            Logger.i("MediaApp", "Asked the app to play a search", mapOf("pkg" to pkg, "query" to query))
            true
        } catch (t: Throwable) {
            Logger.w("MediaApp", "playFromSearch failed", mapOf("pkg" to pkg), t = t)
            false
        }
    }

    /** Resume whatever [pkg] already has loaded. */
    fun play(context: Context, pkg: String): Boolean {
        val controls = session(context, pkg)?.transportControls ?: return false
        return try {
            controls.play()
            Logger.i("MediaApp", "Play sent", mapOf("pkg" to pkg))
            true
        } catch (t: Throwable) {
            Logger.w("MediaApp", "Play failed", mapOf("pkg" to pkg), t = t)
            false
        }
    }

    /** Pause [pkg] specifically, rather than whichever app happens to hold audio focus. */
    fun pause(context: Context, pkg: String): Boolean {
        val c = session(context, pkg) ?: return false
        if (!isActive(c.playbackState?.state)) return false
        return try {
            c.transportControls.pause()
            Logger.i("MediaApp", "Pause sent", mapOf("pkg" to pkg))
            true
        } catch (t: Throwable) {
            Logger.w("MediaApp", "Pause failed", mapOf("pkg" to pkg), t = t)
            false
        }
    }

    /** What is playing, for the log. */
    fun nowPlaying(context: Context, pkg: String): String? {
        val md = session(context, pkg)?.metadata ?: return null
        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        return listOfNotNull(title, artist).joinToString(" - ").ifBlank { null }
    }

    /**
     * States that count as playing. Buffering, connecting and the skipping
     * states are what an app reports between tracks; treating those as stopped
     * would end a block every few minutes.
     */
    private fun isActive(state: Int?): Boolean = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> true
        else -> false
    }

    /**
     * Open [url] in [pkg], over the lock screen if need be.
     *
     * Routed through [KeyguardDismissActivity] for the same reason YouTube Music
     * is: the phone is usually asleep when a block starts, and an activity
     * launched from a service behind the keyguard otherwise goes nowhere.
     */
    fun openDeepLink(context: Context, url: String, pkg: String?) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (pkg != null) setPackage(pkg)
            // CLEAR_TOP matters: without it a deep link sent while the app is
            // already open is delivered to the running screen, which ignored it
            // and left the previous episode loaded.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        Logger.i("MediaApp", "Opening deep link", mapOf("pkg" to (pkg ?: "-"), "url" to url))
        val keyguard = Intent(context, KeyguardDismissActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(KeyguardDismissActivity.EXTRA_LAUNCH, view)
            putExtra(KeyguardDismissActivity.EXTRA_LAUNCH_ID, System.nanoTime())
        }
        try {
            context.startActivity(keyguard)
        } catch (t: Throwable) {
            Logger.w("MediaApp", "Keyguard launch failed; going direct", mapOf("pkg" to (pkg ?: "-")), t = t)
            runCatching { context.startActivity(view) }
        }
    }

    const val ALEPH_BETA_PKG = "org.alephbeta.android"
}
