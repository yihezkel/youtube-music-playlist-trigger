package com.jasonschoenbrun.ytmtrigger.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds and dispatches the intent that opens a playlist in YouTube Music.
 * Shared by [PlaybackTriggerService] (which uses [Strategy.DeepLink]) and
 * the self-test (which uses [Strategy.LauncherThenDeepLink] and
 * [Strategy.CustomScheme] as fallbacks).
 *
 * ## Launch results
 * The intent is not dispatched directly: it is handed to
 * [KeyguardDismissActivity], which wakes the screen and forwards it. That
 * means [launch] returning normally says nothing about whether YouTube Music
 * actually started — the real `startActivity` happens later, in another
 * component. A run record that reported `intentDispatchOk=true` while the
 * forward threw `ActivityNotFoundException` is exactly how the dead
 * `youtubemusic://` scheme went unnoticed.
 *
 * So [launch] returns a launch id, [KeyguardDismissActivity] reports the
 * forward's outcome through [reportResult], and callers can wait for the
 * truth with [awaitResult].
 */
object YtmLauncher {

    const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"

    enum class Strategy { DeepLink, LauncherThenDeepLink, CustomScheme }

    /** Outcome of the real `startActivity` for one [launch] call. */
    data class LaunchResult(val launchId: Long, val ok: Boolean, val error: String?)

    private val seq = AtomicLong(0)
    private val lastResult = AtomicReference<LaunchResult?>(null)
    private val lastLaunchAt = AtomicLong(0)

    /**
     * When we last asked Android to bring YouTube Music up, or 0.
     *
     * Used to tell a dead accessibility binding from a merely quiet one. A
     * launch we initiate always puts YouTube Music's window in front, so it
     * must produce a window-state event; a YouTube Music media session
     * appearing on its own need not, because a resume of an already-running
     * task changes no window.
     */
    fun lastLaunchAtMs(): Long? = lastLaunchAt.get().takeIf { it > 0 }

    /**
     * Launch YT Music using [strategy] for [id].
     *
     * [isTrack] selects a `watch?v=` URL instead of `playlist?list=`. That
     * matters beyond the URL shape: a track deep-link starts playing by
     * itself, so the Play button never has to be found or pressed.
     *
     * Returns a launch id to pass to [awaitResult].
     */
    fun launch(
        context: Context,
        id: String,
        strategy: Strategy,
        isTrack: Boolean = false,
    ): Long {
        val launchId = seq.incrementAndGet()
        lastResult.set(null)
        val path = if (isTrack) "watch?v=$id" else "playlist?list=$id"
        val httpsUri = Uri.parse("https://music.youtube.com/$path")
        val launch: Intent = when (strategy) {
            Strategy.DeepLink -> Intent(Intent.ACTION_VIEW, httpsUri).apply {
                setPackage(YT_MUSIC_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            Strategy.LauncherThenDeepLink -> {
                // Bring YT Music up via its launcher first so the app is
                // foreground+initialised before we send the deep-link.
                val launcher = context.packageManager.getLaunchIntentForPackage(YT_MUSIC_PKG)
                if (launcher != null) {
                    try {
                        launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launcher)
                    } catch (t: Throwable) {
                        Logger.w("YtmLauncher", "Launcher intent failed", t = t)
                    }
                }
                Intent(Intent.ACTION_VIEW, httpsUri).apply {
                    setPackage(YT_MUSIC_PKG)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            }
            Strategy.CustomScheme -> {
                // vnd.youtube.music:// — NOT youtubemusic://, which resolves to
                // nothing on YT Music 9.x and made this strategy dead weight:
                // `cmd package resolve-activity` reports "No activity found"
                // and every attempt threw ActivityNotFoundException.
                //
                // This scheme is worth keeping as the third strategy precisely
                // because it enters through a different activity than the https
                // deep-link (.activities.MusicActivity vs
                // .deeplink.MusicServiceDeepLinkActivity), so it is a genuinely
                // independent path rather than a near-duplicate.
                val customUri = Uri.parse("vnd.youtube.music://$path")
                Intent(Intent.ACTION_VIEW, customUri).apply {
                    setPackage(YT_MUSIC_PKG)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            }
        }
        lastLaunchAt.set(System.currentTimeMillis())
        Logger.i("YtmLauncher", "Launching YT Music", mapOf(
            "strategy" to strategy.name,
            "uri" to launch.dataString.orEmpty(),
            "launchId" to launchId.toString(),
        ))
        val keyguardIntent = Intent(context, KeyguardDismissActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(KeyguardDismissActivity.EXTRA_LAUNCH, launch)
            putExtra(KeyguardDismissActivity.EXTRA_LAUNCH_ID, launchId)
        }
        try {
            context.startActivity(keyguardIntent)
        } catch (t: Throwable) {
            Logger.e("YtmLauncher", "Failed to start KeyguardDismissActivity", t = t)
            // Fall back to dispatching directly; report that outcome ourselves
            // since KeyguardDismissActivity never ran to do it.
            runCatching { context.startActivity(launch) }
                .onSuccess { reportResult(launchId, true, null) }
                .onFailure { t2 ->
                    Logger.e("YtmLauncher", "Direct launch also failed", t = t2)
                    reportResult(launchId, false, describe(t2))
                }
        }
        return launchId
    }

    /** Called by [KeyguardDismissActivity] once it has forwarded the intent. */
    fun reportResult(launchId: Long, ok: Boolean, error: String?) {
        lastResult.set(LaunchResult(launchId, ok, error))
        if (!ok) {
            Logger.w("YtmLauncher", "Launch reported failure", mapOf(
                "launchId" to launchId.toString(),
                "error" to (error ?: ""),
            ))
        }
    }

    /**
     * Wait for the real dispatch outcome of [launchId].
     *
     * Returns null if no result arrived in [timeoutMs] — meaning
     * [KeyguardDismissActivity] never got far enough to report, which is
     * itself a failure worth recording rather than silently calling the
     * dispatch successful.
     */
    suspend fun awaitResult(launchId: Long, timeoutMs: Long = DEFAULT_RESULT_TIMEOUT_MS): LaunchResult? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = lastResult.get()
            if (r != null && r.launchId == launchId) return r
            delay(50)
        }
        return null
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: ""}"

    /** Forwarding happens in the activity's onCreate, normally well under 1s. */
    const val DEFAULT_RESULT_TIMEOUT_MS: Long = 2_500L
}
