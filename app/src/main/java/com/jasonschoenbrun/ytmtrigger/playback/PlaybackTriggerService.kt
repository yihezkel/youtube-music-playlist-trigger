package com.jasonschoenbrun.ytmtrigger.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.jasonschoenbrun.ytmtrigger.YtmApp
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.calendar.HebrewCalendarChecker
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.diag.DiagnosticsSnapshot
import com.jasonschoenbrun.ytmtrigger.diag.FailureLog
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class PlaybackTriggerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduleId = intent?.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID)
        val manual = intent?.getBooleanExtra(AlarmScheduler.EXTRA_MANUAL, false) == true
        val overrideCalendar =
            intent?.getBooleanExtra(AlarmScheduler.EXTRA_OVERRIDE_CALENDAR, false) == true
        active.set(true)
        Logger.i("PlaybackSvc", "onStartCommand", mapOf(
            "scheduleId" to (scheduleId ?: "null"),
            "manual" to manual.toString(),
            "overrideCalendar" to overrideCalendar.toString(),
        ))

        startForeground(NOTIFICATION_ID, buildNotification("Starting playback…"))

        if (scheduleId == null) {
            Logger.e("PlaybackSvc", "No scheduleId; stopping")
            stopSelfSafe()
            return START_NOT_STICKY
        }

        // Shabat / Yom Tov gate. Deliberately the very first thing after the
        // mandatory startForeground and before runFlow, because runFlow's first
        // act is to wake the screen - a visible side effect that must not
        // happen on Shabat. Scheduled triggers can never override; a manual
        // one can, but only when the caller has already shown the user the
        // warning and had it confirmed.
        val cal = HebrewCalendarChecker.check(
            LocalDateTime.now(),
            SettingsRepository.get(this).current().israeliObservance,
        )
        if (cal.skip && !overrideCalendar) {
            val reason = cal.reason ?: "Shabat/Yom Tov"
            // Not a failure: nothing is broken, so this must not reach
            // FailureLog or the alert path.
            Logger.i("PlaybackSvc", "Blocked by calendar", mapOf(
                "scheduleId" to scheduleId,
                "manual" to manual.toString(),
                "reason" to reason,
            ))
            if (manual) {
                Toast.makeText(this, "Not playing - it's $reason.", Toast.LENGTH_LONG).show()
            }
            active.set(false)
            stopSelfSafe()
            return START_NOT_STICKY
        }

        // C-fix-3: cancel any in-flight previous attempt before starting a new one.
        currentJob?.cancel()
        currentJob = scope.launch { runFlow(scheduleId, manual) }
        return START_NOT_STICKY
    }

    private suspend fun runFlow(scheduleId: String, manual: Boolean) {
        acquireScreenWake()
        var fsiNotificationPosted = false
        try {
            // D-fixes: capture a one-shot system diagnostic snapshot at the
            // moment of trigger. Reveals power state, network, audio routing,
            // a11y enabled, foreground app, etc.
            DiagnosticsSnapshot.capture(this, "PlaybackSvc")

            // I-fix-2: high-priority "fix me" notification if a11y is required
            // but not bound. Posted later when we know shuffle/skip are needed.

            // A-fix-3: full-screen-intent notification as a last-resort wake-up.
            fsiNotificationPosted = postFullScreenIntentIfAllowed()

            val repo = ScheduleRepository.get(this)
            val schedule = repo.byId(scheduleId)
                ?: if (scheduleId == MANUAL_DEFAULT_ID) repo.all().firstOrNull { it.enabled } ?: repo.all().firstOrNull() else null
            if (schedule == null) {
                Logger.e("PlaybackSvc", "Schedule not found", mapOf("scheduleId" to scheduleId))
                return
            }

            // Skip if a phone call is active — don't talk over it.
            if (isInCall()) {
                Logger.w("PlaybackSvc", "Skipping: phone call active", mapOf("scheduleId" to scheduleId))
                postFailure("Skipped '${schedule.name}': phone call active")
                return
            }

            val choice = PlaylistPicker.pick(repo, schedule)
            if (choice == null) {
                postFailure("No playlists configured for '${schedule.name}'")
                return
            }
            updateNotification("Launching ${schedule.name}…")

            // Set volume if requested. Falls back to global default if the
            // schedule itself has none configured.
            val effectiveVolume = schedule.targetVolumePercent
                ?: SettingsRepository.get(this).current().defaultVolumePercent
            if (effectiveVolume != null) {
                setMediaVolume(effectiveVolume)
            } else {
                Logger.d("PlaybackSvc", "No target volume configured; leaving as-is")
            }

            val needsAccessibility = schedule.enableShuffle || schedule.skipFirstTrack

            // Auto-heal the A11y service if Android disabled it. Requires the
            // user to have granted WRITE_SECURE_SETTINGS via adb; if they
            // haven't, this is a no-op and the existing alert below fires.
            if (needsAccessibility) {
                val bound = A11yPermissionEnforcer.ensureEnabledAndBound(this)
                if (!bound) {
                    Logger.w("PlaybackSvc", "A11y not bound after auto-heal attempt", mapOf(
                        "hasGrant" to A11yPermissionEnforcer.hasWriteSecureSettings(this).toString(),
                    ))
                }
            }

            // I-fix-2: surface an alert if a11y is required but not running.
            if (needsAccessibility && !YtmAccessibilityService.isRunning()) {
                Logger.e("PlaybackSvc", "A11y required but service not bound")
                postFailure("Accessibility service is OFF — open YTM Trigger and re-enable it under Accessibility settings.")
            }

            // J-fix-1: 3 attempts with exponential backoff 2s -> 5s -> 10s.
            var success = false
            val attemptDelays = longArrayOf(0, 2_000, 5_000)
            for (attempt in 1..MAX_ATTEMPTS) {
                if (attempt > 1) delay(attemptDelays[attempt - 1])
                Logger.i("PlaybackSvc", "Launch attempt", mapOf(
                    "attempt" to attempt.toString(),
                    "maxAttempts" to MAX_ATTEMPTS.toString(),
                    "scheduleId" to schedule.id,
                ))
                if (needsAccessibility) {
                    YtmAccessibilityService.queueAction(
                        com.jasonschoenbrun.ytmtrigger.accessibility.PostLaunchAction(
                            enableShuffle = schedule.enableShuffle,
                            skipFirstTrack = schedule.skipFirstTrack,
                            expectedPlaylistId = choice.playlistId,
                            queuedAtMs = System.currentTimeMillis(),
                        )
                    )
                    Logger.i("PlaybackSvc", "Queued post-launch action", mapOf(
                        "shuffle" to schedule.enableShuffle.toString(),
                        "skip" to schedule.skipFirstTrack.toString(),
                        "playlistId" to choice.playlistId,
                        "accessibilityRunning" to YtmAccessibilityService.isRunning().toString(),
                    ))
                }

                launchYtMusic(choice, LaunchStrategy.DeepLink)

                val playing = waitForPlayback(timeoutMs = PLAYBACK_TIMEOUT_MS)
                if (playing) {
                    success = true
                    repo.recordPlayed(schedule.id, choice.playlistId)
                    Logger.i("PlaybackSvc", "Playback verified", mapOf("attempt" to attempt.toString()))
                    updateNotification("Playing ${schedule.name}")
                    if (needsAccessibility) {
                        val done = YtmAccessibilityService.awaitActionComplete(20_000)
                        Logger.i("PlaybackSvc", "Post-launch action result", mapOf("completed" to done.toString()))
                    }
                    EvalFix.once("J-fix-1-multiAttempt", success = true, mapOf("attempt" to attempt.toString()))
                    break
                } else {
                    Logger.w("PlaybackSvc", "Playback NOT detected", mapOf("attempt" to attempt.toString()))
                }
            }
            if (!success) {
                EvalFix.once("J-fix-1-multiAttempt", success = false, mapOf("attempts" to MAX_ATTEMPTS.toString()))
                postFailure("Playback didn't start for '${schedule.name}' after $MAX_ATTEMPTS attempts — see logs")
            }
        } finally {
            releaseScreenWake()
            if (fsiNotificationPosted) clearFullScreenIntentNotification()
            // Linger a bit so notification doesn't flash off; then stop.
            delay(3000)
            stopSelfSafe()
        }
    }

    private fun isInCall(): Boolean {
        return try {
            // AudioManager.mode reflects call state without needing
            // READ_PHONE_STATE. MODE_IN_CALL = active GSM/CDMA call,
            // MODE_IN_COMMUNICATION = VoIP call.
            val am = getSystemService(AudioManager::class.java) ?: return false
            val mode = am.mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall) Logger.i("PlaybackSvc", "Call detected via AudioManager", mapOf("mode" to mode.toString()))
            inCall
        } catch (t: Throwable) {
            Logger.w("PlaybackSvc", "Call-state check failed", t = t)
            false
        }
    }

    /** Launch strategies — used by both this service (DeepLink) and SelfTestRunner. */
    enum class LaunchStrategy { DeepLink, LauncherThenDeepLink, CustomScheme }

    private suspend fun launchYtMusic(choice: PlaylistPicker.Choice, strategy: LaunchStrategy) {
        val mapped = when (strategy) {
            LaunchStrategy.DeepLink -> YtmLauncher.Strategy.DeepLink
            LaunchStrategy.LauncherThenDeepLink -> YtmLauncher.Strategy.LauncherThenDeepLink
            LaunchStrategy.CustomScheme -> YtmLauncher.Strategy.CustomScheme
        }
        val launchId = YtmLauncher.launch(this, choice.playlistId, mapped)
        // The real startActivity happens in KeyguardDismissActivity, so check
        // what it reported. Without this a failed launch looks identical to a
        // successful one that simply didn't start playing, and we'd burn the
        // whole verification timeout before noticing.
        val result = YtmLauncher.awaitResult(launchId)
        when {
            result == null -> Logger.w("PlaybackSvc", "No launch result reported", mapOf(
                "strategy" to strategy.name, "playlistId" to choice.playlistId,
            ))
            !result.ok -> Logger.e("PlaybackSvc", "Launch intent failed", mapOf(
                "strategy" to strategy.name,
                "playlistId" to choice.playlistId,
                "error" to (result.error ?: ""),
            ))
        }
    }

    /** Public so SelfTestRunner can drive launches through the same code path. */
    internal suspend fun launchForSelfTest(choice: PlaylistPicker.Choice, strategy: LaunchStrategy) {
        launchYtMusic(choice, strategy)
    }

    private suspend fun waitForPlayback(timeoutMs: Long): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        var iter = 0
        var lastComparisonLogMs = 0L
        while (System.currentTimeMillis() < deadline) {
            val audioManagerPlaying = am?.isMusicActive == true
            val sessionStatus = MediaSessionProbe.ytMusicStatus(this)
            val mediaSessionPlaying = sessionStatus is MediaSessionProbe.Status.Playing
            // F-fix-1: prefer MediaSession truth, fall back to AudioManager.
            val playing = mediaSessionPlaying || audioManagerPlaying
            if (iter % 5 == 0) {
                Logger.d("PlaybackSvc", "Playback poll", mapOf(
                    "isMusicActive" to audioManagerPlaying.toString(),
                    "mediaSession" to sessionStatus::class.simpleName.orEmpty(),
                    "elapsedMs" to (timeoutMs - (deadline - System.currentTimeMillis())).toString(),
                ))
            }
            // Comparison log fires once per call; keeps EvalFix volume sane.
            val now = System.currentTimeMillis()
            if (now - lastComparisonLogMs > 5_000) {
                MediaSessionProbe.logComparison(this, audioManagerPlaying)
                lastComparisonLogMs = now
            }
            if (playing) return true
            delay(500)
            iter++
        }
        return false
    }

    private fun setMediaVolume(percent: Int) {
        val am = getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100).coerceAtLeast(0)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        Logger.i("PlaybackSvc", "Set media volume", mapOf("percent" to percent.toString(), "raw" to target.toString(), "max" to max.toString()))
    }

    private fun acquireScreenWake() {
        // A-fix-2: bright screen wakelock with strict 60s timeout. The legacy
        // SCREEN_BRIGHT_WAKE_LOCK is deprecated but still respected by the
        // platform; documented alternatives (KeepScreenOn flag on Activity,
        // setShowWhenLocked) do not by themselves wake the display from
        // off-and-locked. We pair this with KeyguardDismissActivity.
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "YTMT:screenWake"
        )
        wl.setReferenceCounted(false)
        EvalFix.start("A-fix-2-screenWake")
        try {
            wl.acquire(WAKE_LOCK_MS)
            wakeLock = wl
            Logger.i("PlaybackSvc", "Acquired screen wakelock", mapOf("timeoutMs" to WAKE_LOCK_MS.toString()))
            EvalFix.end("A-fix-2-screenWake", success = true)
        } catch (t: Throwable) {
            Logger.e("PlaybackSvc", "Wakelock acquire failed", t = t)
            EvalFix.end("A-fix-2-screenWake", success = false, mapOf("err" to (t.message ?: "")))
        }
    }

    private fun releaseScreenWake() {
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    /**
     * A-fix-3: post a full-screen-intent notification so the system wakes the
     * display and shows our trampoline activity even when the device is
     * locked & dozing. Requires USE_FULL_SCREEN_INTENT permission + (on API
     * 34+) user grant via [NotificationManager.canUseFullScreenIntent].
     *
     * @return true if a notification was actually posted, so the caller can
     *         clear it later.
     */
    private fun postFullScreenIntentIfAllowed(): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            EvalFix.once("A-fix-3-fullScreenIntent", success = false, mapOf("reason" to "noPerm"))
            Logger.w("PlaybackSvc", "Cannot use full-screen-intent (no permission)")
            return false
        }
        val fsiTarget = PendingIntent.getActivity(
            this, 0,
            Intent(this, KeyguardDismissActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, YtmApp.CH_TRIGGER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("YTM Trigger")
            .setContentText("Waking display…")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fsiTarget, true)
            .setAutoCancel(true)
            .build()
        EvalFix.start("A-fix-3-fullScreenIntent")
        return try {
            nm.notify(NOTIFICATION_FSI_ID, n)
            EvalFix.end("A-fix-3-fullScreenIntent", success = true)
            true
        } catch (t: Throwable) {
            EvalFix.end("A-fix-3-fullScreenIntent", success = false, mapOf("err" to (t.message ?: "")))
            Logger.w("PlaybackSvc", "FSI notify failed", t = t)
            false
        }
    }

    private fun clearFullScreenIntentNotification() {
        try { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_FSI_ID) } catch (_: Throwable) {}
    }

    private fun postFailure(msg: String) {
        // Every path that reaches here means the music did not play, which is
        // exactly what the failure list is for.
        FailureLog.record(this, FailureLog.KIND_TRIGGER, msg)
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, YtmApp.CH_FAILURE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Playback failed")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        nm.notify(NOTIFICATION_FAILURE_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, YtmApp.CH_TRIGGER)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("YTM Trigger")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopSelfSafe() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        Logger.i("PlaybackSvc", "onDestroy")
        active.set(false)
        currentJob?.cancel()
        scope.cancel()
        releaseScreenWake()
        super.onDestroy()
    }

    companion object {
        const val MANUAL_DEFAULT_ID = "manual"
        const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_FAILURE_ID = 1002
        const val NOTIFICATION_FSI_ID = 1003
        const val MAX_ATTEMPTS = 3
        const val PLAYBACK_TIMEOUT_MS = 25_000L
        const val WAKE_LOCK_MS = 60_000L

        private val active = java.util.concurrent.atomic.AtomicBoolean(false)

        /** True while a trigger is actually launching and verifying playback. */
        fun isRunning(): Boolean = active.get()

        fun startManual(context: Context, scheduleId: String, overrideCalendar: Boolean = false) {
            val intent = Intent(context, PlaybackTriggerService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(AlarmScheduler.EXTRA_MANUAL, true)
                putExtra(AlarmScheduler.EXTRA_OVERRIDE_CALENDAR, overrideCalendar)
            }
            context.startForegroundService(intent)
        }
    }
}

// Suppress unused warnings for foregroundServiceType reference at compile time.
@Suppress("unused")
private val FGS_TYPES_REF = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
