package com.jasonschoenbrun.ytmtrigger.health

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.data.MediaEntries
import com.jasonschoenbrun.ytmtrigger.data.MediaKind
import com.jasonschoenbrun.ytmtrigger.data.ScheduleChain
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.diag.FailureLog
import com.jasonschoenbrun.ytmtrigger.playback.LockSafeFallback
import com.jasonschoenbrun.ytmtrigger.playback.MediaSessionListenerService
import com.jasonschoenbrun.ytmtrigger.playback.NotifListenerEnforcer
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackPauser

/**
 * One answer to "is this phone still able to do its job?".
 *
 * The three states are not a severity scale; they answer a question about
 * consequence.
 *
 *  - [Ok] — everything the app can do, it can do.
 *  - [Degraded] — something is wrong and the app already handles it. A secure
 *    lock is the case that prompted this: music cannot start, so a podcast is
 *    substituted and the reason announced out loud. Nothing needs doing unless
 *    the workaround is not wanted.
 *  - [Broken] — something is wrong and nothing covers it. A block will be
 *    missed or silent.
 *
 * Something merely untidy does not get to turn the button red. The colour
 * answers one question: do I need to go and look at the phone?
 */
enum class Health { Ok, Degraded, Broken }

data class Check(
    val title: String,
    val health: Health,
    /** What is true right now. */
    val detail: String,
    /** What it costs, and what covers it, when not [Health.Ok]. */
    val consequence: String? = null,
    /** Where to go to put it right, when there is somewhere. */
    val fixAction: String? = null,
)

data class HealthReport(val checks: List<Check>) {
    val overall: Health = when {
        checks.any { it.health == Health.Broken } -> Health.Broken
        checks.any { it.health == Health.Degraded } -> Health.Degraded
        else -> Health.Ok
    }
    val brokenCount = checks.count { it.health == Health.Broken }
    val degradedCount = checks.count { it.health == Health.Degraded }

    val summary: String
        get() = when (overall) {
            Health.Ok -> "Everything works"
            Health.Degraded ->
                if (degradedCount == 1) "1 thing is being worked around"
                else "$degradedCount things are being worked around"
            Health.Broken ->
                if (brokenCount == 1) "1 thing is broken" else "$brokenCount things are broken"
        }
}

object HealthChecks {

    fun run(context: Context): HealthReport = HealthReport(
        listOf(
            exactAlarms(context),
            batteryOptimisation(context),
            backgroundRestricted(context),
            enabledSchedules(context),
            alarmsArmed(context),
            scheduleChains(context),
            automaticTime(context),
            screenLock(context),
            accessibility(context),
            notificationListener(context),
            notifications(context),
            ytMusicInstalled(context),
            network(context),
            mediaVolume(context),
            playbackPaused(),
            recentFailures(context),
        )
    )

    // --- things that stop the app working at all ----------------------------

    private fun exactAlarms(context: Context): Check {
        val ok = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        return Check(
            title = "Exact alarms",
            health = if (ok) Health.Ok else Health.Broken,
            detail = if (ok) "Allowed" else "Not allowed",
            consequence = if (ok) null else
                "Nothing will play. Every block is an exact alarm, and without this " +
                    "permission Android will not schedule one.",
            fixAction = if (ok) null else "Settings › Apps › YTM Trigger › Alarms & reminders",
        )
    }

    private fun batteryOptimisation(context: Context): Check {
        val pm = context.getSystemService(PowerManager::class.java)
        val ok = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        return Check(
            title = "Battery optimisation",
            health = if (ok) Health.Ok else Health.Broken,
            detail = if (ok) "Exempt" else "Not exempt",
            consequence = if (ok) null else
                "Blocks will be missed. Android will doze the app between triggers, so " +
                    "alarms fire late or not at all.",
            fixAction = if (ok) null else "Settings › Apps › YTM Trigger › Battery › Unrestricted",
        )
    }

    private fun backgroundRestricted(context: Context): Check {
        val am = context.getSystemService(ActivityManager::class.java)
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            am?.isBackgroundRestricted == true
        return Check(
            title = "Background activity",
            health = if (restricted) Health.Broken else Health.Ok,
            detail = if (restricted) "Restricted" else "Allowed",
            consequence = if (restricted)
                "Blocks will be missed. A background-restricted app cannot start its " +
                    "playback service when an alarm fires."
            else null,
            fixAction = if (restricted) "Settings › Apps › YTM Trigger › Battery" else null,
        )
    }

    private fun enabledSchedules(context: Context): Check {
        val enabled = ScheduleRepository.get(context).all().filter { it.enabled }
        val playable = enabled.filter { s -> s.playlistUrls.any { MediaEntries.isValid(it) } }
        return when {
            enabled.isEmpty() -> Check(
                "Schedules", Health.Broken, "None enabled",
                "Nothing will ever play. Every schedule is switched off.",
                "Schedules screen",
            )
            playable.isEmpty() -> Check(
                "Schedules", Health.Broken,
                "${enabled.size} enabled, none with anything to play",
                "Nothing will play. The enabled schedules hold no recognisable playlist or feed.",
                "Schedules screen",
            )
            playable.size < enabled.size -> Check(
                "Schedules", Health.Degraded,
                "${playable.size} of ${enabled.size} have something to play",
                "The rest will fire and find nothing.",
                "Schedules screen",
            )
            else -> Check("Schedules", Health.Ok, "${enabled.size} enabled, all with entries")
        }
    }

    private fun alarmsArmed(context: Context): Check {
        // A schedule that follows another block is started by that block, never
        // by the clock, so it is not expected to have a next trigger time.
        val enabled = ScheduleRepository.get(context).all()
            .filter { it.enabled && it.startsAfter == null }
        val armed = enabled.count { AlarmScheduler.computeNextTriggerMs(context, it) != null }
        return when {
            enabled.isEmpty() -> Check("Alarms armed", Health.Ok, "Nothing to arm")
            armed == enabled.size -> Check("Alarms armed", Health.Ok, "$armed of ${enabled.size}")
            armed == 0 -> Check(
                "Alarms armed", Health.Broken, "0 of ${enabled.size}",
                "Nothing will play. No enabled schedule has a next trigger time, which " +
                    "usually means a calendar-anchored schedule cannot work out its anchor.",
                "Settings › location, used for sunset times",
            )
            else -> Check(
                "Alarms armed", Health.Degraded, "$armed of ${enabled.size}",
                "Some schedules have no next trigger. One anchored to the end of Shabat " +
                    "yields nothing in a week with no such moment, which is normal; a " +
                    "permanent gap is not.",
            )
        }
    }

    /**
     * The "follows another block" links.
     *
     * Only the schedule generator writes these today, so a broken link is
     * unlikely — but when one is broken the symptom is silence with no error,
     * which is exactly the shape of fault this screen exists to surface.
     */
    private fun scheduleChains(context: Context): Check {
        val all = ScheduleRepository.get(context).all()
        val chained = all.count { it.startsAfter != null }
        val problems = ScheduleChain.problems(all)
        if (chained == 0) return Check("Block chaining", Health.Ok, "No chained blocks")
        if (problems.isEmpty()) {
            return Check("Block chaining", Health.Ok, "$chained chained, all resolved")
        }
        return Check(
            "Block chaining", Health.Broken,
            "${problems.size} broken",
            problems.joinToString("  ") { "\"${it.scheduleName}\" ${it.detail}." } +
                "  A chained block is never armed from the clock, so it plays only when the " +
                "block it names finishes. If that link is broken it simply never runs.",
            "Schedules",
        )
    }

    private fun automaticTime(context: Context): Check {
        fun flag(name: String) = try {
            Settings.Global.getInt(context.contentResolver, name, 0) == 1
        } catch (_: Throwable) { true }
        val auto = flag(Settings.Global.AUTO_TIME)
        val autoZone = flag(Settings.Global.AUTO_TIME_ZONE)
        val ok = auto && autoZone
        val which = when {
            !auto && !autoZone -> "time and time zone"
            !auto -> "time"
            else -> "time zone"
        }
        return Check(
            title = "Clock",
            health = if (ok) Health.Ok else Health.Degraded,
            detail = if (ok) "Set automatically" else "Manual $which",
            consequence = if (ok) null else
                "Blocks will drift. Every trigger time, and every sunset and nightfall " +
                    "calculation, is measured against this clock.",
            fixAction = if (ok) null else "Settings › System › Date & time",
        )
    }

    // --- things the app already works around --------------------------------

    private fun screenLock(context: Context): Check {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardSecure != true) {
            return Check("Screen lock", Health.Ok, "None, or swipe only")
        }
        // How bad a secure lock is depends entirely on whether a substitute
        // exists, so work that out rather than assuming the worst or the best.
        val enabled = ScheduleRepository.get(context).all().filter { it.enabled }
        val defaultsHavePodcast = SettingsRepository.get(context).current().defaultPlaylistUrls
            .any { LockSafeFallback.playsWhileLocked(MediaEntries.parse(it).kind) }
        val stranded = enabled.count { s ->
            val hasBlocked = s.playlistUrls.any {
                !LockSafeFallback.playsWhileLocked(MediaEntries.parse(it).kind)
            }
            val hasSubstitute = s.playlistUrls.any {
                LockSafeFallback.playsWhileLocked(MediaEntries.parse(it).kind)
            } || defaultsHavePodcast
            hasBlocked && !hasSubstitute
        }
        return if (stranded == 0) {
            Check(
                "Screen lock", Health.Degraded, "PIN, pattern or password",
                "YouTube Music cannot start while the phone is locked: Android will not let " +
                    "an app open another app's screen over a secure keyguard. Podcasts are " +
                    "unaffected, and a music entry is replaced by a podcast with a spoken " +
                    "explanation. A swipe-only lock avoids this entirely.",
                "Settings › Security › Screen lock",
            )
        } else {
            Check(
                "Screen lock", Health.Broken,
                "PIN set, and $stranded schedule(s) have no substitute",
                "Those blocks will be silent: they contain only music, and the default " +
                    "entries in Settings hold no podcast to fall back on.",
                "Add a podcast to the Settings defaults",
            )
        }
    }

    private fun accessibility(context: Context): Check {
        // Checked before the ordinary "is it bound" question, because a dead
        // binding reports itself as bound and healthy everywhere else.
        if (A11yPermissionEnforcer.isProvablyDead()) {
            return Check(
                "Accessibility service", Health.Broken,
                "Bound but delivering nothing",
                "YouTube Music has run since this service connected and the service received " +
                    "no events at all, so it cannot press Play and music will not start. " +
                    "Podcasts are unaffected. Restarting the app does not fix this and neither " +
                    "does switching the service off and on — the phone has to be rebooted.",
                "Restart the phone",
            )
        }
        if (YtmAccessibilityService.isRunning()) {
            return Check("Accessibility service", Health.Ok, "Running")
        }
        val canHeal = A11yPermissionEnforcer.hasWriteSecureSettings(context)
        return Check(
            "Accessibility service", Health.Degraded,
            if (canHeal) "Not running - can be switched back on automatically" else "Not running",
            "YouTube Music will not start: the deep link opens the playlist page, and " +
                "pressing Play needs this service. Podcasts are unaffected." +
                if (canHeal) " The app can re-enable it itself, and tries before every trigger."
                else " Without the WRITE_SECURE_SETTINGS grant it cannot be re-enabled automatically.",
            "Settings › Accessibility › YTM Trigger",
        )
    }

    private fun notificationListener(context: Context): Check {
        val ok = NotifListenerEnforcer.isEnabled(context)
        return Check(
            title = "Media session access",
            health = if (ok) Health.Ok else Health.Degraded,
            detail = if (ok) "Granted" else "Not granted",
            consequence = if (ok) null else
                "Playback becomes guesswork. Without it the app cannot see what is playing, " +
                    "so stopping at a block's end falls back to a media key aimed at whichever " +
                    "app holds audio focus, and the end of a playlist cannot be detected.",
            fixAction = if (ok) null else "Settings › Notifications › Device & app notifications",
        )
    }

    private fun notifications(context: Context): Check {
        val ok = context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() == true
        return Check(
            title = "Notifications",
            health = if (ok) Health.Ok else Health.Degraded,
            detail = if (ok) "Allowed" else "Blocked",
            consequence = if (ok) null else
                "Playback still works, but failures become silent - the app reports problems " +
                    "by notification.",
            fixAction = if (ok) null else "Settings › Apps › YTM Trigger › Notifications",
        )
    }

    private fun ytMusicInstalled(context: Context): Check {
        val installed = try {
            context.packageManager.getPackageInfo(MediaSessionListenerService.YT_MUSIC_PKG, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            true
        }
        if (installed) return Check("YouTube Music", Health.Ok, "Installed")
        val wantsMusic = ScheduleRepository.get(context).all()
            .filter { it.enabled }
            .any { s ->
                s.playlistUrls.any {
                    val k = MediaEntries.parse(it).kind
                    k == MediaKind.YtmPlaylist || k == MediaKind.YtmTrack
                }
            }
        return if (!wantsMusic) {
            Check("YouTube Music", Health.Ok, "Not installed, and nothing needs it")
        } else {
            Check(
                "YouTube Music", Health.Degraded, "Not installed",
                "Music entries cannot play. Podcasts are unaffected and are substituted.",
                "Install YouTube Music",
            )
        }
    }

    private fun network(context: Context): Check {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return Check(
            title = "Network",
            health = if (online) Health.Ok else Health.Degraded,
            detail = if (online) "Connected" else "Offline",
            consequence = if (online) null else
                "Podcasts fall back to their cached feed, which is kept for exactly this, " +
                    "but the audio itself is streamed and will not play. Nothing new can be fetched.",
        )
    }

    private fun mediaVolume(context: Context): Check {
        val am = context.getSystemService(AudioManager::class.java)
        val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
        val pct = if (max > 0) cur * 100 / max else 0
        if (pct > 0) return Check("Media volume", Health.Ok, "$pct%")
        // Muted is only a problem when nothing will raise it: a schedule with a
        // target volume sets it at trigger time.
        val settings = SettingsRepository.get(context).current()
        val anySetsVolume = settings.defaultVolumePercent != null ||
            ScheduleRepository.get(context).all().any { it.enabled && it.targetVolumePercent != null }
        return if (anySetsVolume) {
            Check("Media volume", Health.Ok, "Muted now, but set on every trigger")
        } else {
            Check(
                "Media volume", Health.Degraded, "Muted, and nothing will raise it",
                "Blocks will run silently. No schedule sets a target volume, so the phone " +
                    "plays at whatever it was left at.",
                "Set a default volume in Settings",
            )
        }
    }

    /**
     * A pause left in force.
     *
     * Every other check here asks whether playback *could* start. None of them
     * notices that it has been deliberately held, which is why the motzaei
     * Shabat blocks on 29 Aug went quiet a minute into each and stayed quiet for
     * two hours with the screen reporting all fifteen checks fine. A pause is
     * legitimate, so this is never Broken; it just has to be visible.
     */
    private fun playbackPaused(): Check {
        val heldMs = PlaybackPauser.pausedForMs()
            ?: return Check("Playback", Health.Ok, "Not paused")
        val mins = heldMs / 60_000
        val forHowLong = when {
            mins < 1L -> "just now"
            mins < 60L -> "$mins min ago"
            else -> "${mins / 60}h ${mins % 60}m ago"
        }
        // Short pauses are the feature working. A pause nobody has come back to
        // is the thing worth surfacing.
        val level = if (mins >= PAUSE_STALE_MIN) Health.Degraded else Health.Ok
        return Check(
            "Playback", level, "Paused $forHowLong",
            if (level == Health.Degraded) {
                "Playback was paused from the app and has not been resumed. Nothing will " +
                    "play until it is resumed, the block is stopped, or the next block starts."
            } else null,
            if (level == Health.Degraded) "Resume on the home screen" else null,
        )
    }

    /** How long a pause may sit before it is worth mentioning. */
    private const val PAUSE_STALE_MIN = 15L

    private fun recentFailures(context: Context): Check {
        val entries = try {
            FailureLog.recent(context, days = 7)
        } catch (_: Throwable) {
            emptyList()
        }
        if (entries.isEmpty()) return Check("Recent failures", Health.Ok, "None in the last 7 days")
        // Only today's failures colour the light. The question this screen
        // answers is whether the phone can do its job now, and something that
        // failed four days ago and has worked since is history rather than a
        // present fault - often the cause is already fixed. Older failures are
        // still reported, because a pattern is worth seeing.
        //
        // "Today" has to mean the calendar day. This counted a rolling 24 hours
        // instead, so at 15:32 it reached back to 15:32 the previous day and
        // reported eight failures "today" on a day that had none - every one of
        // them from a test run the night before. Reuse the per-day counts the
        // chart is drawn from, so the number under the light and the last bar
        // of the chart cannot disagree.
        val today = FailureLog.dailyCounts(entries).last()
        return if (today == 0) {
            Check(
                "Recent failures", Health.Ok,
                "${entries.size} in the last 7 days, none today",
                fixAction = "Self-test screen lists each one",
            )
        } else {
            Check(
                "Recent failures", Health.Degraded,
                "$today today, ${entries.size} in the last 7 days",
                "Something went wrong today. The Self-test screen lists each one.",
                "Self-test screen",
            )
        }
    }
}
