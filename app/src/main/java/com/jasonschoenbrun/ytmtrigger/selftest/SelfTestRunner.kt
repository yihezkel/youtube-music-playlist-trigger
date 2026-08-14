package com.jasonschoenbrun.ytmtrigger.selftest

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.accessibility.PostLaunchAction
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.data.PlaylistUrl
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.diag.A11yActionResult
import com.jasonschoenbrun.ytmtrigger.diag.AttemptOutcome
import com.jasonschoenbrun.ytmtrigger.diag.DiagnosticsSnapshot
import com.jasonschoenbrun.ytmtrigger.diag.DiagnosticsSnapshotData
import com.jasonschoenbrun.ytmtrigger.diag.RunOutcome
import com.jasonschoenbrun.ytmtrigger.diag.SelfTestRunRecord
import com.jasonschoenbrun.ytmtrigger.diag.StrategyAttempt
import com.jasonschoenbrun.ytmtrigger.diag.TimelineSample
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.MediaSessionListenerService
import com.jasonschoenbrun.ytmtrigger.playback.MediaSessionProbe
import com.jasonschoenbrun.ytmtrigger.playback.NotifListenerEnforcer
import com.jasonschoenbrun.ytmtrigger.playback.YtmLauncher
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.util.UUID

/**
 * Runs a non-destructive end-to-end self-test and builds a forensic
 * [SelfTestRunRecord] describing exactly what happened — every strategy
 * tried, every intent dispatch outcome, MediaSession/audio timelines during
 * each wait, the A11y action's step-by-step trace, and a per-attempt
 * diagnostic snapshot of system state.
 *
 * The record is what callers should persist; the legacy "did this pass or
 * fail" answer lives at [SelfTestRunRecord.outcome].
 */
object SelfTestRunner {

    private const val STRATEGY_TIMEOUT_MS: Long = 25_000
    private const val INTER_STRATEGY_DELAY_MS: Long = 1_500
    /** Aggressive poll for responsiveness — we want to detect playback fast. */
    private const val POLL_INTERVAL_MS: Long = 300
    /** Slower sample for the timeline so the JSON record stays compact. */
    private const val TIMELINE_SAMPLE_INTERVAL_MS: Long = 1_000
    /** Wait this long for the queued A11y action coroutine to finish so we
     *  can attach its step trace to the attempt. */
    private const val A11Y_RESULT_TIMEOUT_MS: Long = 6_000

    /** What kicked off this run. Manual bypasses the Shabat/Yom Tov check. */
    enum class Trigger(val wire: String) {
        Scheduled("scheduled"),
        Manual("manual"),
        BootRecovery("boot-recovery"),
    }

    /**
     * Run the self-test. Always returns a [SelfTestRunRecord]; callers should
     * inspect [SelfTestRunRecord.outcome] to decide success/failure handling.
     */
    suspend fun run(context: Context, trigger: Trigger): SelfTestRunRecord {
        val runId = UUID.randomUUID().toString()
        val startMs = System.currentTimeMillis()
        val settings = SettingsRepository.get(context).current()
        val (appVersionName, appVersionCode) = appVersionOf(context)
        val (ytmVersionName, ytmVersionCode) = ytmVersionOf(context)
        val manual = trigger == Trigger.Manual

        // 1. Shabat / Yom Tov check. Manual runs bypass it — if the user
        //    explicitly tapped "Run now" they want to test the setup,
        //    regardless of what day it is.
        if (!manual) {
            val cal = HebrewCalendarChecker.check(LocalDateTime.now(), settings.israeliObservance)
            if (cal.skip) {
                val reason = cal.reason ?: "Shabat/Yom Tov"
                Logger.i("SelfTest", "Skipped (calendar)", mapOf("reason" to reason, "runId" to runId))
                return finishRecord(
                    runId = runId, startMs = startMs, trigger = trigger,
                    outcome = RunOutcome.Skipped(reason),
                    playlistId = null, attempts = emptyList(),
                    preflight = DiagnosticsSnapshot.captureData(context, "SelfTest"),
                    postFailure = null,
                    appVersionName = appVersionName, appVersionCode = appVersionCode,
                    ytmVersionName = ytmVersionName, ytmVersionCode = ytmVersionCode,
                )
            }
        } else {
            Logger.i("SelfTest", "Manual run — calendar check bypassed", mapOf("runId" to runId))
        }

        // 2. Already-playing check. We use BOTH signals so we never accidentally
        // interrupt music that's already playing (whether YTM or another app).
        val am = context.getSystemService(AudioManager::class.java)
        val audioActive = am?.isMusicActive == true
        val sessionStatus = MediaSessionProbe.ytMusicStatus(context)
        if (audioActive || sessionStatus is MediaSessionProbe.Status.Playing) {
            val r = "music already playing (audioManager=$audioActive, mediaSession=${sessionStatus::class.simpleName})"
            Logger.i("SelfTest", "Skipped (busy)", mapOf("reason" to r, "runId" to runId))
            return finishRecord(
                runId = runId, startMs = startMs, trigger = trigger,
                outcome = RunOutcome.Skipped(r),
                playlistId = null, attempts = emptyList(),
                preflight = DiagnosticsSnapshot.captureData(context, "SelfTest"),
                postFailure = null,
                appVersionName = appVersionName, appVersionCode = appVersionCode,
                ytmVersionName = ytmVersionName, ytmVersionCode = ytmVersionCode,
            )
        }

        // 3. Capture diagnostics so even a passing self-test has system state on disk.
        //    The classic helper writes per-field log lines so today's log file
        //    remains the same shape we've been reading manually; captureData()
        //    builds the structured snapshot we attach to the record.
        DiagnosticsSnapshot.capture(context, "SelfTest")
        val preflight = DiagnosticsSnapshot.captureData(context, "SelfTest-preflight")

        // Pick the test playlist. Prefer explicit selfTestPlaylistUrl; otherwise
        // first defaultPlaylistUrl; if both empty, fail the test up-front (the
        // alert tells the user to configure something).
        val testUrl = settings.selfTestPlaylistUrl
            ?: settings.defaultPlaylistUrls.firstOrNull()
        if (testUrl.isNullOrBlank()) {
            Logger.w("SelfTest", "No playlist configured for self-test", mapOf("runId" to runId))
            return finishRecord(
                runId = runId, startMs = startMs, trigger = trigger,
                outcome = RunOutcome.ConfigError("No self-test playlist configured. Add a default playlist or set a self-test playlist in settings."),
                playlistId = null, attempts = emptyList(),
                preflight = preflight, postFailure = null,
                appVersionName = appVersionName, appVersionCode = appVersionCode,
                ytmVersionName = ytmVersionName, ytmVersionCode = ytmVersionCode,
            )
        }
        val playlistId = PlaylistUrl.extractId(testUrl)
        if (playlistId == null) {
            Logger.w("SelfTest", "Could not extract playlist id", mapOf("url" to testUrl, "runId" to runId))
            return finishRecord(
                runId = runId, startMs = startMs, trigger = trigger,
                outcome = RunOutcome.ConfigError("Self-test playlist URL is not a valid YouTube Music playlist."),
                playlistId = null, attempts = emptyList(),
                preflight = preflight, postFailure = null,
                appVersionName = appVersionName, appVersionCode = appVersionCode,
                ytmVersionName = ytmVersionName, ytmVersionCode = ytmVersionCode,
            )
        }

        // 4. Save volume, then go silent for the entire test cycle.
        val savedVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        am?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        Logger.i("SelfTest", "Volume muted for test", mapOf(
            "savedVolume" to savedVolume.toString(), "runId" to runId,
        ))

        val attempts = ArrayList<StrategyAttempt>(3)
        val tried = mutableListOf<String>()
        var winner: YtmLauncher.Strategy? = null
        var winnerElapsedMs: Long = 0
        var postFailure: DiagnosticsSnapshotData? = null

        try {
            val strategies = listOf(
                YtmLauncher.Strategy.DeepLink,
                YtmLauncher.Strategy.LauncherThenDeepLink,
                YtmLauncher.Strategy.CustomScheme,
            )
            // Auto-heal the A11y service if Android has disabled it. Requires
            // the user to have granted WRITE_SECURE_SETTINGS via adb; if they
            // haven't, this is a no-op and the explicit warning below fires.
            val a11yRunning = A11yPermissionEnforcer.ensureEnabledAndBound(context)
            if (!a11yRunning) {
                Logger.w(
                    "SelfTest",
                    "A11y service NOT running — self-test cannot press Play and will fail. " +
                        "Enable accessibility for this app in system settings, or grant " +
                        "WRITE_SECURE_SETTINGS via adb so the app can self-heal.",
                    mapOf("grant" to A11yPermissionEnforcer.adbGrantCommand(context), "runId" to runId),
                )
            }
            // Without the notification listener, MediaSessionProbe throws
            // SecurityException and every mediaSessionTimeline sample in this
            // run's record would read "Unavailable". Not fatal — playback
            // detection falls back to AudioManager — but it costs us the more
            // reliable signal. It can't be self-granted, so just wait for the
            // bind if it's approved and record the gap if it isn't.
            if (!NotifListenerEnforcer.awaitConnected(context)) {
                Logger.w(
                    "SelfTest",
                    "Notification listener not connected — MediaSession timeline will be Unavailable; " +
                        "falling back to AudioManager.isMusicActive",
                    mapOf("allow" to NotifListenerEnforcer.adbAllowCommand(context), "runId" to runId),
                )
            }
            for ((i, strat) in strategies.withIndex()) {
                if (i > 0) delay(INTER_STRATEGY_DELAY_MS)
                tried += strat.name
                Logger.i("SelfTest", "Trying strategy", mapOf("strategy" to strat.name, "runId" to runId))
                EvalFix.start("SelfTest-${strat.name}")
                val attempt = runStrategyAttempt(
                    context = context,
                    am = am,
                    strategy = strat,
                    playlistId = playlistId,
                    a11yRunning = a11yRunning,
                    runId = runId,
                )
                attempts += attempt
                EvalFix.end("SelfTest-${strat.name}",
                    success = attempt.outcome == AttemptOutcome.Succeeded,
                    mapOf("elapsedMs" to (attempt.endedAtMs - attempt.startedAtMs).toString()),
                )
                if (attempt.outcome == AttemptOutcome.Succeeded) {
                    winner = strat
                    winnerElapsedMs = attempt.endedAtMs - attempt.startedAtMs
                    stopYtMusic(context)
                    break
                }
                // Make sure no zombie session is left running before next strategy.
                stopYtMusic(context)
            }

            val outcome: RunOutcome = if (winner != null) {
                Logger.i("SelfTest", "Strategy succeeded", mapOf(
                    "strategy" to winner.name, "elapsedMs" to winnerElapsedMs.toString(), "runId" to runId,
                ))
                RunOutcome.Success(winner.name, winnerElapsedMs)
            } else {
                // Capture a follow-up snapshot to see what changed between
                // the start and the end of the test.
                DiagnosticsSnapshot.capture(context, "SelfTest-post-fail")
                postFailure = DiagnosticsSnapshot.captureData(context, "SelfTest-post-fail")
                Logger.w("SelfTest", "All strategies failed", mapOf(
                    "tried" to tried.joinToString(","), "runId" to runId,
                ))
                RunOutcome.AllFailed(
                    tried = tried.toList(),
                    summary = "All ${tried.size} strategies failed within ${STRATEGY_TIMEOUT_MS / 1000}s each.",
                )
            }

            return finishRecord(
                runId = runId, startMs = startMs, trigger = trigger,
                outcome = outcome, playlistId = playlistId, attempts = attempts,
                preflight = preflight, postFailure = postFailure,
                appVersionName = appVersionName, appVersionCode = appVersionCode,
                ytmVersionName = ytmVersionName, ytmVersionCode = ytmVersionCode,
            )
        } finally {
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            Logger.i("SelfTest", "Volume restored", mapOf(
                "savedVolume" to savedVolume.toString(), "runId" to runId,
            ))
        }
    }

    /**
     * Run a single launch strategy and return a [StrategyAttempt] capturing
     * what happened — intent dispatch outcome, MediaSession / audio /
     * foreground timelines during the wait, the A11y action's step trace
     * (or a synthetic "never started" trace), and a cheap per-attempt
     * diagnostic re-snapshot.
     */
    private suspend fun runStrategyAttempt(
        context: Context,
        am: AudioManager?,
        strategy: YtmLauncher.Strategy,
        playlistId: String,
        a11yRunning: Boolean,
        runId: String,
    ): StrategyAttempt {
        val attemptStart = System.currentTimeMillis()
        val perAttemptDiag = DiagnosticsSnapshot.captureData(context, "SelfTest-attempt-${strategy.name}")

        // Queue an A11y action so the service presses YT Music's Play
        // button as soon as the playlist page loads. Without this, the
        // deep-link merely opens the playlist page and we'd time out.
        // We use shuffle=false / skip=false because the self-test only
        // needs to verify that playback CAN start — it shouldn't perturb
        // the playlist state with shuffle/skip side-effects.
        if (a11yRunning) {
            YtmAccessibilityService.queueAction(
                PostLaunchAction(
                    enableShuffle = false,
                    skipFirstTrack = false,
                    expectedPlaylistId = playlistId,
                    queuedAtMs = System.currentTimeMillis(),
                    runId = runId,
                )
            )
            Logger.i("SelfTest", "Queued A11y press-play", mapOf(
                "playlistId" to playlistId, "runId" to runId,
            ))
        }

        // Dispatch the launch intent. Capture any exception so the attempt
        // record clearly distinguishes "intent threw" from "intent dispatched
        // but playback never started."
        // Declared as `var` because Kotlin cannot prove definite assignment
        // across a try/catch: the try body may throw partway through, so a
        // `val` assigned in both branches is rejected.
        var intentDispatchOk = false
        var intentException: String? = null
        try {
            YtmLauncher.launch(context, playlistId, strategy)
            intentDispatchOk = true
        } catch (t: Throwable) {
            Logger.e("SelfTest", "Launch intent threw", mapOf(
                "strategy" to strategy.name, "runId" to runId,
            ), t = t)
            intentException = "${t.javaClass.simpleName}: ${t.message ?: ""}"
        }

        val waitResult = waitForPlay(context, am, attemptStart)
        val outcome = if (waitResult.gotPlay) AttemptOutcome.Succeeded else AttemptOutcome.TimedOut

        // Wait briefly for the A11y action coroutine to finish so its step
        // trace is included. Always returns a record — synthetic when no
        // action ever ran (e.g. YT Music never came foreground).
        val a11yResult: A11yActionResult? = if (a11yRunning) {
            YtmAccessibilityService.awaitActionResult(A11Y_RESULT_TIMEOUT_MS)
        } else {
            null
        }

        val attemptEnd = System.currentTimeMillis()
        Logger.i("SelfTest", "Attempt finished", mapOf(
            "strategy" to strategy.name,
            "outcome" to outcome.name,
            "elapsedMs" to (attemptEnd - attemptStart).toString(),
            "ytmCameToForeground" to waitResult.ytmCameToForeground.toString(),
            "a11yStarted" to (a11yResult?.started?.toString() ?: "n/a"),
            "a11ySteps" to (a11yResult?.steps?.size?.toString() ?: "0"),
            "runId" to runId,
        ))
        return StrategyAttempt(
            strategy = strategy.name,
            startedAtMs = attemptStart,
            endedAtMs = attemptEnd,
            outcome = outcome,
            intentDispatchOk = intentDispatchOk,
            intentException = intentException,
            ytmCameToForeground = waitResult.ytmCameToForeground,
            foregroundAppAtEnd = waitResult.lastForegroundApp,
            mediaSessionTimeline = waitResult.mediaTimeline,
            audioActiveTimeline = waitResult.audioTimeline,
            a11yActionResult = a11yResult,
            perAttemptDiagnostics = perAttemptDiag,
        )
    }

    private data class WaitResult(
        val gotPlay: Boolean,
        val mediaTimeline: List<TimelineSample>,
        val audioTimeline: List<TimelineSample>,
        val ytmCameToForeground: Boolean,
        val lastForegroundApp: String?,
    )

    /**
     * Poll for playback at [POLL_INTERVAL_MS] cadence, but only sample the
     * timelines (and foreground app) every [TIMELINE_SAMPLE_INTERVAL_MS] so
     * the resulting record stays compact (≤25 samples per timeline).
     */
    private suspend fun waitForPlay(
        context: Context,
        am: AudioManager?,
        attemptStartMs: Long,
    ): WaitResult {
        val deadline = attemptStartMs + STRATEGY_TIMEOUT_MS
        val mediaTimeline = ArrayList<TimelineSample>(30)
        val audioTimeline = ArrayList<TimelineSample>(30)
        var nextTimelineMs = attemptStartMs
        var ytmEverForeground = false
        var lastForegroundApp: String? = null
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val status = MediaSessionProbe.ytMusicStatus(context)
            val audioPlaying = am?.isMusicActive == true
            if (now >= nextTimelineMs) {
                val elapsed = now - attemptStartMs
                mediaTimeline += TimelineSample(elapsed, status::class.simpleName.orEmpty())
                audioTimeline += TimelineSample(elapsed, audioPlaying.toString())
                val fg = currentForegroundApp(context)
                if (fg != null) {
                    lastForegroundApp = fg
                    if (fg == YtmAccessibilityService.YT_MUSIC_PKG) ytmEverForeground = true
                }
                nextTimelineMs = now + TIMELINE_SAMPLE_INTERVAL_MS
            }
            if (status is MediaSessionProbe.Status.Playing) {
                return WaitResult(true, mediaTimeline, audioTimeline, ytmEverForeground, lastForegroundApp)
            }
            // Fallback: isMusicActive often goes true before the MediaSession
            // is observable. Accept it as a positive signal too, but require it
            // to remain true on a second poll to avoid a transient false hit.
            if (audioPlaying) {
                delay(POLL_INTERVAL_MS)
                if (am?.isMusicActive == true) {
                    return WaitResult(true, mediaTimeline, audioTimeline, ytmEverForeground, lastForegroundApp)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return WaitResult(false, mediaTimeline, audioTimeline, ytmEverForeground, lastForegroundApp)
    }

    /**
     * Returns the package name of whatever app is currently foreground.
     *
     * Prefers the accessibility service, which already knows this and needs
     * no extra permission. Falls back to UsageStats, which silently yields
     * nothing unless PACKAGE_USAGE_STATS is granted — an appop the app cannot
     * grant itself. Relying on UsageStats alone made [ytmCameToForeground] a
     * false negative on devices without that grant.
     */
    private fun currentForegroundApp(context: Context): String? {
        YtmAccessibilityService.currentForegroundPackage()?.let { return it }
        return try {
            val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5_000, now)
            var last: String? = null
            val ev = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = ev.packageName
                }
            }
            last
        } catch (_: Throwable) { null }
    }

    private fun finishRecord(
        runId: String,
        startMs: Long,
        trigger: Trigger,
        outcome: RunOutcome,
        playlistId: String?,
        attempts: List<StrategyAttempt>,
        preflight: DiagnosticsSnapshotData,
        postFailure: DiagnosticsSnapshotData?,
        appVersionName: String,
        appVersionCode: Long,
        ytmVersionName: String?,
        ytmVersionCode: Long?,
    ): SelfTestRunRecord = SelfTestRunRecord(
        runId = runId,
        startedAtMs = startMs,
        endedAtMs = System.currentTimeMillis(),
        trigger = trigger.wire,
        outcome = outcome,
        playlistId = playlistId,
        attempts = attempts,
        preflight = preflight,
        postFailure = postFailure,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        ytmVersionName = ytmVersionName,
        ytmVersionCode = ytmVersionCode,
    )

    private fun appVersionOf(context: Context): Pair<String, Long> {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Throwable) { return "" to 0L }
        return (info.versionName ?: "") to info.longVersionCode
    }

    private fun ytmVersionOf(context: Context): Pair<String?, Long?> {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(YtmAccessibilityService.YT_MUSIC_PKG, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(YtmAccessibilityService.YT_MUSIC_PKG, 0)
            }
        } catch (_: Throwable) { return null to null }
        return info.versionName to info.longVersionCode
    }

    /**
     * Pause YT Music. We try two mechanisms in order:
     *
     *  1. [MediaController.TransportControls.pause] — only works if the user
     *     has granted us the notification-listener permission (so we can list
     *     active media sessions). Most reliable when available.
     *  2. Fallback: [AudioManager.dispatchMediaKeyEvent] with
     *     [KeyEvent.KEYCODE_MEDIA_PAUSE]. Works without any special permission
     *     and routes to whichever app currently owns audio focus — which is
     *     YT Music since it just started playing.
     *
     * The worst case (both paths fail) is that the user hears at most a
     * fraction of a second of audio at volume 0 before the volume restore
     * undoes the silencing.
     */
    private fun stopYtMusic(context: Context) {
        if (tryMediaControllerPause(context)) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Logger.i("SelfTest", "Pause dispatched via KEYCODE_MEDIA_PAUSE")
        } catch (t: Throwable) {
            Logger.w("SelfTest", "Pause dispatch failed", t = t)
        }
    }

    /** Returns true if a pause was successfully sent via MediaController. */
    private fun tryMediaControllerPause(context: Context): Boolean {
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
            Logger.i("SelfTest", "Pause sent via MediaController")
            true
        } catch (t: Throwable) {
            Logger.w("SelfTest", "MediaController pause failed", t = t)
            false
        }
    }
}
