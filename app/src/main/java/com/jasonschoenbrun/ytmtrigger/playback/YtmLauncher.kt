package com.jasonschoenbrun.ytmtrigger.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Builds and dispatches the intent that opens a playlist in YouTube Music.
 * Shared by [PlaybackTriggerService] (which uses [Strategy.DeepLink]) and
 * the self-test (which uses [Strategy.LauncherThenDeepLink] and
 * [Strategy.CustomScheme] as fallbacks).
 */
object YtmLauncher {

    const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"

    enum class Strategy { DeepLink, LauncherThenDeepLink, CustomScheme }

    /**
     * Launch YT Music using [strategy] for the playlist identified by
     * [playlistId]. The intent is wrapped in [KeyguardDismissActivity] so the
     * screen wakes & the keyguard is dismissed.
     */
    fun launch(context: Context, playlistId: String, strategy: Strategy) {
        val httpsUri = Uri.parse("https://music.youtube.com/playlist?list=$playlistId")
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
                val customUri = Uri.parse("youtubemusic://playlist?list=$playlistId")
                Intent(Intent.ACTION_VIEW, customUri).apply {
                    setPackage(YT_MUSIC_PKG)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            }
        }
        Logger.i("YtmLauncher", "Launching YT Music", mapOf(
            "strategy" to strategy.name,
            "uri" to launch.dataString.orEmpty(),
        ))
        val keyguardIntent = Intent(context, KeyguardDismissActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(KeyguardDismissActivity.EXTRA_LAUNCH, launch)
        }
        try {
            context.startActivity(keyguardIntent)
        } catch (t: Throwable) {
            Logger.e("YtmLauncher", "Failed to start KeyguardDismissActivity", t = t)
            runCatching { context.startActivity(launch) }
                .onFailure { t2 -> Logger.e("YtmLauncher", "Direct launch also failed", t = t2) }
        }
    }
}
