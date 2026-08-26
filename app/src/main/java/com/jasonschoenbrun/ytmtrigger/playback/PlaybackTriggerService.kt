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
import com.jasonschoenbrun.ytmtrigger.calendar.calendarConfig
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.data.MediaEntries
import com.jasonschoenbrun.ytmtrigger.data.MediaEntry
import com.jasonschoenbrun.ytmtrigger.data.MediaKind
import com.jasonschoenbrun.ytmtrigger.data.PodcastEpisodeMode
import com.jasonschoenbrun.ytmtrigger.podcast.PodcastCatalog
import com.jasonschoenbrun.ytmtrigger.podcast.PodcastPlayerService
import com.jasonschoenbrun.ytmtrigger.podcast.PodcastResume
import com.jasonschoenbrun.ytmtrigger.podcast.PodcastSequence
import com.jasonschoenbrun.ytmtrigger.podcast.SpotifyFeedResolver
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
import kotlin.random.Random

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
        // Which entry of a continuous schedule to play. -1 means "first trigger
        // of the block"; the podcast player passes the next index when an
        // episode ends. Chained triggers still pass through the Shabat gate
        // below, so a queue can never outlive the start of Shabat.
        val queueIndex = intent?.getIntExtra(EXTRA_QUEUE_INDEX, -1) ?: -1
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
            SettingsRepository.get(this).current().calendarConfig(),
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
        currentJob = scope.launch { runFlow(scheduleId, manual, queueIndex) }
        return START_NOT_STICKY
    }

    private suspend fun runFlow(scheduleId: String, manual: Boolean, queueIndex: Int = -1) {
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

            // Whether there is enough of the block left to be worth starting
            // the next episode is decided in playPodcast, where the episode's
            // own length is known. A fixed number of seconds cannot answer it:
            // the same two minutes is nearly all of a short clip and almost
            // none of an hour-long shiur.

            // A continuous schedule walks its entries in order and wraps, so
            // the block keeps filling; every other schedule picks one entry.
            //
            // A block with no stop time is the last of its day. It runs its
            // queue once and finishes with the last episode rather than looping
            // back to the top, which would otherwise play on all night.
            val endsWithQueue = schedule.continuousPlay && schedule.stopTimeMinutes == null
            val choice = if (schedule.continuousPlay) {
                PlaylistPicker.at(schedule, if (queueIndex < 0) 0 else queueIndex, wrap = !endsWithQueue)
            } else {
                PlaylistPicker.pick(repo, schedule)
            }
            if (choice == null) {
                // Having played something already means the pool is not empty,
                // so this is the queue running out, not a misconfiguration.
                if (endsWithQueue && queueIndex > 0) {
                    Logger.i("PlaybackSvc", "Queue finished; block ends", mapOf(
                        "scheduleId" to schedule.id,
                        "itemsPlayed" to queueIndex.toString(),
                    ))
                    // Hand on to whatever follows this block. Chaining rather
                    // than arming a clock time is the point: how long this
                    // queue ran depends on the episodes it drew, so no fixed
                    // offset would land on its end.
                    val next = repo.all().firstOrNull { it.enabled && it.startsAfter == schedule.id }
                    if (next != null) {
                        Logger.i("PlaybackSvc", "Starting the block that follows", mapOf(
                            "after" to schedule.id, "next" to next.id, "name" to next.name,
                        ))
                        startQueued(this, next.id, 0)
                    }
                } else {
                    postFailure("No playlists configured for '${schedule.name}'")
                }
                return
            }
            // Arm the stop time on the first item only. Re-arming mid-queue
            // would be harmless but pointless, and it muddies the log.
            if (queueIndex <= 0) AlarmScheduler.scheduleStop(this, schedule)

            // Set the volume before dispatching anywhere. This used to sit
            // further down, after the podcast branch had already returned, so
            // a podcast-only schedule never set the volume at all - and once
            // the phone was muted for any reason it stayed muted, playing to
            // nobody. Only on the first item of a queue, so turning the volume
            // down mid-block is not undone by the next episode.
            if (queueIndex <= 0) {
                val effectiveVolume = schedule.targetVolumePercent
                    ?: SettingsRepository.get(this).current().defaultVolumePercent
                if (effectiveVolume != null) {
                    setMediaVolume(effectiveVolume)
                } else {
                    Logger.d("PlaybackSvc", "No target volume configured; leaving as-is")
                }
            }

            // Podcasts and single tracks don't go through the YT Music
            // playlist flow at all: a feed is played by us, and a track
            // deep-link starts playing on its own.
            // Carry the label across: choice.url is already stripped of it.
            val entry = MediaEntries.parse(choice.url)
                .copy(label = choice.label, episodeMode = choice.episodeMode)
            if (entry.kind == MediaKind.PodcastFeed || entry.kind == MediaKind.SpotifyShow) {
                val played = playPodcast(schedule, entry, choice.index)
                if (!played) postFailure("Could not play podcast for '${schedule.name}'")
                return
            }

            updateNotification("Launching ${schedule.name}…")

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
        val launchId = YtmLauncher.launch(this, choice.playlistId, mapped, isTrack = choice.kind == MediaKind.YtmTrack)
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

    private fun stopSelfSafe() {        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        stopSelf()
    }

    /**
     * Resolve an episode and hand it to [PodcastPlayerService].
     *
     * Safe to do network I/O here: this runs inside the service's coroutine
     * scope on [Dispatchers.Default], not on the main thread.
     */
    /**
     * Seconds until [schedule]'s stop time, or null when it has none.
     *
     * Mirrors [AlarmScheduler.scheduleStop]'s rule that a stop at or before the
     * current time belongs to the following day, so an overnight block is not
     * mistaken for one that has already ended.
     */
    private fun secondsUntilStop(schedule: Schedule): Long? {
        val stopTime = schedule.stopLocalTime() ?: return null
        val now = LocalDateTime.now()
        var stop = LocalDateTime.of(now.toLocalDate(), stopTime)
        if (!stop.isAfter(now)) stop = stop.plusDays(1)
        return java.time.Duration.between(now, stop).seconds
    }

    private suspend fun playPodcast(schedule: Schedule, entry: MediaEntry, queueIndex: Int): Boolean {
        val feedUrl = when (entry.kind) {
            MediaKind.PodcastFeed -> entry.id
            MediaKind.SpotifyShow -> SpotifyFeedResolver.feedForShow(this, entry.id) ?: run {
                Logger.e("PlaybackSvc", "No RSS feed found for Spotify show", mapOf(
                    "show" to entry.id, "name" to entry.displayName,
                ))
                return false
            }
            else -> return false
        }
        val episodes = PodcastCatalog.episodes(this, feedUrl)
        if (episodes.isEmpty()) {
            Logger.e("PlaybackSvc", "Feed produced no episodes", mapOf("feed" to feedUrl))
            return false
        }
        // An episode left part-heard when a block ended takes precedence over
        // picking a new one: finishing what was started is the point of the
        // resume feature, and dropping back a few minutes re-establishes
        // context rather than resuming mid-sentence.
        val pending = PodcastResume.get(this, feedUrl)
        val resumeEpisode = pending?.let { m -> episodes.firstOrNull { it.audioUrl == m.audioUrl } }
        // A per-entry mode wins over the schedule's: one block legitimately
        // mixes a news feed that must be newest with an archive that should be
        // shuffled and a serial that has to run in order.
        val mode = entry.episodeMode ?: schedule.podcastEpisodeMode
        val chosen = resumeEpisode ?: when (mode) {
            PodcastEpisodeMode.Latest -> episodes.first()
            PodcastEpisodeMode.Random -> episodes[Random.nextInt(episodes.size)]
            PodcastEpisodeMode.Sequential -> PodcastSequence.next(this, feedUrl, episodes)
        } ?: episodes.first()
        val startAtSec = if (resumeEpisode != null && pending != null) {
            PodcastResume.resumeAtSec(pending)
        } else 0L

        // Don't start an episode the block cannot get meaningfully through.
        // The threshold is a share of the episode, not a fixed number of
        // seconds: hearing two minutes of a 71-minute shiur is not worth the
        // interruption, whereas two minutes of a three-minute clip is nearly
        // all of it. Episodes whose feed omits a duration are always played -
        // unknown must not become "skip".
        val remainingSec = secondsUntilStop(schedule)
        val playableSec = (chosen.durationSec ?: 0L) - startAtSec
        if (queueIndex > 0 && remainingSec != null && playableSec > 0 &&
            remainingSec < playableSec * MIN_EPISODE_SHARE
        ) {
            Logger.i("PlaybackSvc", "Not starting episode; too little of it would play", mapOf(
                "scheduleId" to schedule.id,
                "podcast" to entry.displayName,
                "title" to chosen.title,
                "secondsLeft" to remainingSec.toString(),
                "episodeSec" to playableSec.toString(),
                "sharePlayable" to "%.2f".format(remainingSec.toDouble() / playableSec),
            ))
            return true
        }

        Logger.i("PlaybackSvc", "Podcast episode chosen", mapOf(
            "podcast" to entry.displayName,
            "mode" to if (resumeEpisode != null) "Resume" else mode.name,
            "modeFrom" to if (entry.episodeMode != null) "entry" else "schedule",
            "episodes" to episodes.size.toString(),
            "title" to chosen.title,
            "startAtSec" to startAtSec.toString(),
            "feedDurationSec" to (chosen.durationSec?.toString() ?: "unknown"),
            "secondsLeftInBlock" to (remainingSec?.toString() ?: "no stop"),
        ))
        updateNotification("Playing ${entry.displayName}…")
        PodcastPlayerService.start(
            this, chosen.audioUrl, chosen.title,
            queueScheduleId = if (schedule.continuousPlay) schedule.id else null,
            queueIndex = queueIndex,
            feedUrl = feedUrl,
            startAtSec = startAtSec,
        )
        // Verify rather than assume, mirroring the YT Music path.
        val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PodcastPlayerService.isPlaying()) {
                Logger.i("PlaybackSvc", "Podcast playback verified", mapOf("title" to chosen.title))
                return true
            }
            delay(500)
        }
        Logger.e("PlaybackSvc", "Podcast did not start in time", mapOf("title" to chosen.title))
        return false
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
        const val EXTRA_QUEUE_INDEX = "queueIndex"
        /**
         * How close to a block's stop time is too close to start another
         * episode. Small on purpose - see the guard's comment in runFlow.
         */
        /**
         * The least of an episode that must fit in what remains of a block for
         * it to be worth starting. Below this the block simply ends early;
         * with resume in place the episode is not lost, only deferred.
         */
        const val MIN_EPISODE_SHARE = 0.5
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

        /**
         * Continue a continuous schedule at [queueIndex].
         *
         * Called by the podcast player when an episode ends. It deliberately
         * re-enters the normal trigger path, so the Shabat gate, the in-call
         * check and failure reporting apply to every item in a queue rather
         * than only the first.
         */
        fun startQueued(context: Context, scheduleId: String, queueIndex: Int) {
            val intent = Intent(context, PlaybackTriggerService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(EXTRA_QUEUE_INDEX, queueIndex)
            }
            context.startForegroundService(intent)
        }
    }
}

// Suppress unused warnings for foregroundServiceType reference at compile time.
@Suppress("unused")
private val FGS_TYPES_REF = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
