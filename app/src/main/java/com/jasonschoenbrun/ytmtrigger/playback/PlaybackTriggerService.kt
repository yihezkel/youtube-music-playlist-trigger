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
import com.jasonschoenbrun.ytmtrigger.alarm.AutoStop
import com.jasonschoenbrun.ytmtrigger.calendar.HebrewCalendarChecker
import com.jasonschoenbrun.ytmtrigger.calendar.calendarConfig
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleChain
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
        // New playback supersedes any pause the user left behind, including a
        // block paused and never resumed before the next one came round.
        PlaybackPauser.clear("new trigger")
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

            // Any watch on this schedule belongs to the item that just ended.
            // Cancelled here rather than per-branch so it cannot outlive its
            // entry down any path, including the ones that return early.
            MusicEndWatcher.cancel(this, schedule.id)

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
            // A block with no stop of any kind is the last of its day. It runs
            // its queue once and finishes with the last episode rather than
            // looping back to the top, which would otherwise play on all night.
            // A duration-stopped block is a timed block like any other, so it
            // still wraps to stay full until its stop.
            val endsWithQueue = schedule.continuousPlay &&
                schedule.stopTimeMinutes == null && schedule.autoStopMinutes == null
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
                    val next = ScheduleChain.next(repo.all(), schedule.id)
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
                .copy(
                    label = choice.label,
                    episodeMode = choice.episodeMode,
                    minMinutes = choice.minMinutes,
                )
            if (entry.kind == MediaKind.PodcastFeed || entry.kind == MediaKind.SpotifyShow) {
                // Declining an over-long episode is a deliberate outcome, not a
                // failure: the block simply ends early rather than starting
                // something it cannot get through.
                val outcome = playPodcast(schedule, entry, choice.index)
                if (outcome == PodcastOutcome.Failed) {
                    postFailure("Could not play podcast for '${schedule.name}'")
                }
                return
            }

            // Everything from here is played by another app, which means opening
            // its screen - and a secure lock forbids that. Checked once, up
            // front, rather than after three launch attempts have failed and the
            // accessibility service has aborted with "systemui bouncer is
            // foreground". Podcasts above are unaffected, which is why the check
            // sits here and not at the top.
            LockScreenGuard.log(this, schedule.id)
            val lockProblem = LockScreenGuard.describe(this)
            if (lockProblem != null) {
                val name = choice.label ?: entry.displayName
                // Before giving up: a media session is not a window. Transport
                // controls are service calls, so a keyguard has nothing to
                // refuse, and YouTube Music advertises PLAY_FROM_URI and
                // PLAY_FROM_SEARCH on the session it publishes. If it honours
                // either, music plays while locked after all.
                if (entry.kind == MediaKind.YtmPlaylist || entry.kind == MediaKind.YtmTrack) {
                    if (playViaSession(schedule, choice, entry)) return
                }
                Logger.e("PlaybackSvc", "Cannot start an app-played entry", mapOf(
                    "scheduleId" to schedule.id, "entry" to name, "why" to lockProblem,
                ))
                // Play the part of the block that still works, and say why.
                // Silence would be the worse answer: the household expected
                // something at this hour, and podcasts play behind a lock
                // perfectly well.
                if (playLockSafeSubstitute(schedule, choice.index, name)) return
                postFailure("Couldn't play '$name' for '${schedule.name}': $lockProblem")
                return
            }

            if (entry.kind == MediaKind.AlephBeta) {
                val played = playAlephBeta(schedule, entry, choice.index)
                if (!played && !skipToNextEntry(schedule, choice.index, "could not start")) {
                    postFailure("Could not play '${entry.displayName}' for '${schedule.name}' in the Aleph Beta app")
                }
                return
            }

            updateNotification("Launching ${schedule.name}…")

            // Everything below is the YouTube Music path; podcasts returned above.
            //
            // The accessibility service is needed for every YT Music entry, not
            // only when shuffle or a skip is wanted. The deep link opens the
            // playlist page without starting it - pressing Play is step 1 of the
            // post-launch action. This used to be `enableShuffle ||
            // skipFirstTrack`, so a schedule with both switched off launched YT
            // Music, never pressed Play, failed all three attempts and left the
            // playlist sitting on screen in silence.
            val needsAccessibility = true

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
                    // Nothing tells us when a YT Music playlist ends, so ask
                    // periodically instead. Without this the queue stopped dead
                    // at the first music entry and the rest of the block was
                    // silent once the playlist ran out.
                    if (schedule.continuousPlay) {
                        MusicEndWatcher.watch(this, schedule.id, choice.index)
                    }
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

    /**
     * Wait until YouTube Music is actually playing.
     *
     * The bar is a YT Music media session in a playing state. AudioManager's
     * isMusicActive is true for *any* app's audio, so it used to report success
     * while the previous podcast in the same queue was still finishing - a
     * launch was once "verified" 133 ms after the intent was sent, which YT
     * Music cannot possibly have managed. It is still consulted, but only once
     * no other app is holding a playing session, so it can cover the case where
     * YT Music plays without publishing one.
     */
    private suspend fun waitForPlayback(timeoutMs: Long): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        var iter = 0
        var lastComparisonLogMs = 0L
        while (System.currentTimeMillis() < deadline) {
            val audioManagerPlaying = am?.isMusicActive == true
            val sessionStatus = MediaSessionProbe.ytMusicStatus(this)
            val mediaSessionPlaying = sessionStatus is MediaSessionProbe.Status.Playing
            // Someone else's audio must not be mistaken for ours.
            val otherApp = MediaAppController.playingPackages(this)
                .firstOrNull { it != MediaSessionListenerService.YT_MUSIC_PKG }
            val playing = mediaSessionPlaying || (audioManagerPlaying && otherApp == null)
            if (iter % 5 == 0) {
                Logger.d("PlaybackSvc", "Playback poll", mapOf(
                    "isMusicActive" to audioManagerPlaying.toString(),
                    "mediaSession" to sessionStatus::class.simpleName.orEmpty(),
                    "otherAppPlaying" to (otherApp ?: "-"),
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

    /**
     * Hand a continuous block on to its next entry when this one cannot play.
     *
     * Without this a single unplayable entry ended the whole block: the trigger
     * reported a failure and returned, and nothing scheduled after it ever ran.
     * That was tolerable while music was always last in a queue, and stopped
     * being so once the end-of-playlist watch made mid-queue music possible.
     *
     * @return true when the queue was advanced and the caller should stop.
     */
    private fun skipToNextEntry(schedule: Schedule, index: Int, why: String): Boolean {
        if (!schedule.continuousPlay) return false
        val entries = schedule.playlistUrls.size
        // Only within this lap: wrapping here would retry the same unplayable
        // entry for as long as the block lasts.
        if (index + 1 >= entries) return false
        Logger.i("PlaybackSvc", "Skipping to the next entry", mapOf(
            "scheduleId" to schedule.id,
            "from" to index.toString(),
            "to" to (index + 1).toString(),
            "why" to why,
        ))
        startQueued(this, schedule.id, index + 1)
        return true
    }

    /**
     * Start YouTube Music through its media session rather than its screen.
     *
     * The screen is what a secure lock forbids. A media session is not a window:
     * transport controls are service calls, so the keyguard has nothing to
     * refuse, and any app with notification-listener access may send them - no
     * allowlist, unlike the MediaBrowserService, which YouTube Music reserves
     * for Android Auto and car head units and refused us outright.
     *
     * YouTube Music advertises PLAY_FROM_URI, PLAY_FROM_MEDIA_ID and
     * PLAY_FROM_SEARCH. Advertising is not honouring, though - the Aleph Beta
     * app advertises the same and acts on none of them - so both are tried and
     * the result is measured rather than assumed.
     *
     * It needs a session to talk to, which means YouTube Music must have been
     * used at least once since it was last force-stopped. That is the honest
     * limit of this route.
     */
    private suspend fun playViaSession(
        schedule: Schedule,
        choice: PlaylistPicker.Choice,
        entry: MediaEntry,
    ): Boolean {
        val pkg = YT_MUSIC_PKG
        if (MediaAppController.session(this, pkg) == null) {
            Logger.w("PlaybackSvc", "No YouTube Music session to drive while locked", mapOf(
                "scheduleId" to schedule.id,
            ))
            return false
        }
        val name = choice.label ?: entry.displayName
        // Whether it was already playing decides what a "playing" reading means
        // afterwards. Without this, arriving while YouTube Music happens to be
        // playing looks exactly like having started it - which is how a run
        // once reported success 8 ms after the call, faster than YouTube Music
        // could possibly have acted.
        val alreadyPlaying = MediaAppController.isPlaying(this, pkg)
        if (alreadyPlaying) {
            Logger.i("PlaybackSvc", "YouTube Music was already playing; leaving it alone", mapOf(
                "scheduleId" to schedule.id,
                "wanted" to name,
                "nowPlaying" to (MediaAppController.nowPlaying(this, pkg) ?: "-"),
            ))
            if (schedule.continuousPlay) {
                MusicEndWatcher.watch(this, schedule.id, choice.index, pkg)
            }
            return true
        }
        // The URL first: it names the exact playlist, where a search only names
        // something like it.
        val attempts = listOf<Pair<String, () -> Boolean>>(
            "playFromUri" to { MediaAppController.playFromUri(this, pkg, choice.url) },
            "playFromSearch" to { MediaAppController.playFromSearch(this, pkg, name) },
        )
        for ((how, send) in attempts) {
            if (!send()) continue
            val deadline = System.currentTimeMillis() + SESSION_START_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (MediaAppController.isPlaying(this, pkg)) {
                    Logger.i("PlaybackSvc", "Music started while locked, via the media session", mapOf(
                        "scheduleId" to schedule.id, "how" to how, "entry" to name,
                        "nowPlaying" to (MediaAppController.nowPlaying(this, pkg) ?: "-"),
                    ))
                    updateNotification("Playing ${schedule.name}")
                    if (schedule.continuousPlay) {
                        MusicEndWatcher.watch(this, schedule.id, choice.index, pkg)
                    }
                    return true
                }
                delay(1000)
            }
            Logger.w("PlaybackSvc", "Session route did not start playback", mapOf(
                "how" to how, "entry" to name,
            ))
        }
        return false
    }

    /**
     * Play something that survives a locked phone, having said why.
     *
     * Preferring the block's own queue keeps the hour close to what was
     * intended - the household picked those shows for this time - and only a
     * block with nothing playable in it at all falls back to the Settings
     * defaults.
     *
     * The announcement is spoken rather than posted, because nobody is looking
     * at a phone in a kitchen, and it is awaited so the substitute does not
     * talk over its own explanation.
     *
     * @return true when something was started.
     */
    private suspend fun playLockSafeSubstitute(
        schedule: Schedule,
        blockedIndex: Int,
        blockedName: String,
    ): Boolean {
        val candidates = LockSafeFallback.findAll(this, schedule, blockedIndex)
        if (candidates.isEmpty()) return false
        // Tried in order, because the first choice can still be declined for
        // being too long for what is left of the block - which is exactly the
        // situation a substitute is likely to meet, arriving late in a block.
        // Announcing per attempt keeps the room told the truth rather than told
        // about something that never played.
        for (choice in candidates) {
            Logger.i("PlaybackSvc", "Substituting a lock-safe entry", mapOf(
                "scheduleId" to schedule.id,
                "blocked" to blockedName,
                "playing" to choice.label,
                "source" to if (choice.fromSettings) "settings defaults" else "this block",
                "queueIndex" to (choice.queueIndex?.toString() ?: "-"),
            ))
            Announcer.say(this, LockSafeFallback.announcement(blockedName, choice))

            // An entry from this block keeps its place in the queue, so what
            // follows it still follows it. One borrowed from Settings has no
            // place in the queue, so it plays as a one-off.
            val parsed = MediaEntries.parse(MediaEntries.url(choice.entry))
                .copy(
                    label = MediaEntries.label(choice.entry),
                    episodeMode = MediaEntries.episodeMode(choice.entry),
                    minMinutes = MediaEntries.minMinutes(choice.entry),
                )
            val index = choice.queueIndex ?: blockedIndex
            when (playPodcast(schedule, parsed, index)) {
                PodcastOutcome.Started -> return true
                PodcastOutcome.TooLittleTimeLeft -> Logger.i(
                    "PlaybackSvc", "Substitute too long for what is left; trying the next",
                    mapOf("entry" to choice.label),
                )
                PodcastOutcome.Failed -> Logger.w(
                    "PlaybackSvc", "Substitute would not play; trying the next",
                    mapOf("entry" to choice.label),
                )
            }
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
        val stopTime = schedule.stopLocalTime() ?: run {
            // Duration-stopped block: the absolute stop was written down when
            // the block started, since the start time is no longer available.
            val at = AutoStop.endsAt(this, schedule.id) ?: return null
            return ((at - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
        val now = LocalDateTime.now()
        var stop = LocalDateTime.of(now.toLocalDate(), stopTime)
        if (!stop.isAfter(now)) stop = stop.plusDays(1)
        return java.time.Duration.between(now, stop).seconds
    }

    /**
     * What came of trying to play a podcast entry.
     *
     * "Handled" and "playing" used to be the same answer, which was fine while
     * the only caller was the normal queue - a block declining an over-long
     * episode simply ends early, and that is correct. It stopped being fine
     * once a substitute could be chosen for a locked phone: the substitute would
     * be declined for the same reason, report success, and leave silence.
     */
    private enum class PodcastOutcome {
        /** Audio is playing. */
        Started,
        /** Deliberately not started: too little of the block was left for it. */
        TooLittleTimeLeft,
        /** Wanted to play and could not. */
        Failed,
    }

    private suspend fun playPodcast(schedule: Schedule, entry: MediaEntry, queueIndex: Int): PodcastOutcome {
        val feedUrl = when (entry.kind) {
            MediaKind.PodcastFeed -> entry.id
            MediaKind.SpotifyShow -> SpotifyFeedResolver.feedForShow(this, entry.id) ?: run {
                Logger.e("PlaybackSvc", "No RSS feed found for Spotify show", mapOf(
                    "show" to entry.id, "name" to entry.displayName,
                ))
                return PodcastOutcome.Failed
            }
            else -> return PodcastOutcome.Failed
        }
        val episodes = PodcastCatalog.episodes(this, feedUrl)
        if (episodes.isEmpty()) {
            Logger.e("PlaybackSvc", "Feed produced no episodes", mapOf("feed" to feedUrl))
            return PodcastOutcome.Failed
        }
        // An episode left part-heard when a block ended takes precedence over
        // picking a new one: finishing what was started is the point of the
        // resume feature, and dropping back a few minutes re-establishes
        // context rather than resuming mid-sentence.
        val pending = PodcastResume.get(this, feedUrl)
        val resumeEpisode = pending?.let { m -> episodes.firstOrNull { it.audioUrl == m.audioUrl } }
        // A per-entry minimum length, for feeds carrying two formats under one
        // name - see MediaEntry. Applied to the candidate pool only, so an
        // episode already part-heard is still finished even if it falls below
        // the floor. Episodes with no duration are kept, matching the rule the
        // half-episode check below follows: unknown must not mean "skip". If
        // the floor would leave nothing at all it is ignored rather than
        // dropping the show from the block, because a filter that empties a
        // feed is a mistake in the filter, not an instruction to go silent.
        val minSec = (entry.minMinutes ?: 0) * 60L
        val pool = if (minSec <= 0L) episodes else {
            val kept = episodes.filter { (it.durationSec ?: Long.MAX_VALUE) >= minSec }
            if (kept.isEmpty()) {
                Logger.w("PlaybackSvc", "Minimum length excluded every episode; ignoring it", mapOf(
                    "podcast" to entry.displayName,
                    "minMinutes" to entry.minMinutes.toString(),
                    "episodes" to episodes.size.toString(),
                ))
                episodes
            } else kept
        }
        // A per-entry mode wins over the schedule's: one block legitimately
        // mixes a news feed that must be newest with an archive that should be
        // shuffled and a serial that has to run in order.
        val mode = entry.episodeMode ?: schedule.podcastEpisodeMode
        val chosen = resumeEpisode ?: when (mode) {
            PodcastEpisodeMode.Latest -> pool.first()
            PodcastEpisodeMode.Random -> pool[Random.nextInt(pool.size)]
            PodcastEpisodeMode.Sequential -> PodcastSequence.next(this, feedUrl, pool)
        } ?: pool.first()
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
            return PodcastOutcome.TooLittleTimeLeft
        }

        Logger.i("PlaybackSvc", "Podcast episode chosen", mapOf(
            "podcast" to entry.displayName,
            "mode" to if (resumeEpisode != null) "Resume" else mode.name,
            "modeFrom" to if (entry.episodeMode != null) "entry" else "schedule",
            "episodes" to episodes.size.toString(),
            "eligible" to pool.size.toString(),
            "minMinutes" to (entry.minMinutes?.toString() ?: "none"),
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
                return PodcastOutcome.Started
            }
            delay(500)
        }
        Logger.e("PlaybackSvc", "Podcast did not start in time", mapOf("title" to chosen.title))
        return PodcastOutcome.Failed
    }

    /**
     * Play something from the Aleph Beta app.
     *
     * Their public feeds carry only the series in progress, so a subscription's
     * archive is reachable only through the app. It is driven the way YouTube
     * Music is - opened at the right place, then watched through the media
     * session it publishes - rather than screen-scraped.
     *
     * Two ways in, tried in order. The deep link opens the app on the show, and
     * their App Links are verified for both alephbeta.org hosts so it never
     * bounces through a browser. If that alone does not start playback, the
     * session's own PLAY_FROM_SEARCH is asked for the entry by name; that is a
     * documented request an app opts into, and it needs no screen coordinates.
     */
    private suspend fun playAlephBeta(schedule: Schedule, entry: MediaEntry, queueIndex: Int): Boolean {
        val pkg = MediaAppController.ALEPH_BETA_PKG
        val name = entry.displayName
        updateNotification("Opening ${name}…")

        MediaAppController.openDeepLink(this, entry.id, pkg)
        // Give the app time to come up and lay the page out. Tapping or asking
        // before it has finished is what produced a Source error by hand.
        delay(ALEPH_BETA_LOAD_MS)

        // Three ways to start it, weakest assumption first.
        //
        // The deep link loads the item into their player but does not start it,
        // so a plain play() on the session it publishes is the most direct
        // thing to try. Failing that, ask it for the entry by name.
        if (!MediaAppController.isPlaying(this, pkg)) {
            val resumed = MediaAppController.play(this, pkg)
            Logger.i("PlaybackSvc", "Asked Aleph Beta to play what is loaded", mapOf(
                "podcast" to name, "delivered" to resumed.toString(),
                "state" to MusicEndWatcher.stillPlaying(this, pkg).second,
            ))
            delay(4000)
        }
        if (!MediaAppController.isPlaying(this, pkg)) {
            val asked = MediaAppController.playFromSearch(this, pkg, name)
            Logger.i("PlaybackSvc", "Asked Aleph Beta to play by name", mapOf(
                "podcast" to name, "delivered" to asked.toString(),
            ))
        }

        val deadline = System.currentTimeMillis() + ALEPH_BETA_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (MediaAppController.isPlaying(this, pkg)) {
                Logger.i("PlaybackSvc", "Aleph Beta playback verified", mapOf(
                    "podcast" to name,
                    "nowPlaying" to (MediaAppController.nowPlaying(this, pkg) ?: "-"),
                    "secondsLeftInBlock" to (secondsUntilStop(schedule)?.toString() ?: "no stop"),
                ))
                updateNotification("Playing $name…")
                // Their app reports no end, exactly like YouTube Music, so the
                // queue moves on when the session stops rather than when we are
                // told anything.
                if (schedule.continuousPlay) {
                    MusicEndWatcher.watch(this, schedule.id, queueIndex, pkg)
                }
                return true
            }
            delay(1000)
        }
        Logger.e("PlaybackSvc", "Aleph Beta did not start playing", mapOf(
            "podcast" to name,
            "url" to entry.id,
            "state" to MusicEndWatcher.stillPlaying(this, pkg).second,
        ))
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
        /** How long to wait for the media-session route to produce audio. */
        const val SESSION_START_TIMEOUT_MS = 20_000L
        /** How long to let the Aleph Beta app lay itself out before asking it to play. */
        const val ALEPH_BETA_LOAD_MS = 12_000L
        /** How long to wait for it to actually start. */
        const val ALEPH_BETA_START_TIMEOUT_MS = 45_000L
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
