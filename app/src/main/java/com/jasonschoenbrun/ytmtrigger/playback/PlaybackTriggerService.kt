package com.jasonschoenbrun.ytmtrigger.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jasonschoenbrun.ytmtrigger.YtmApp
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackTriggerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduleId = intent?.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID)
        val manual = intent?.getBooleanExtra(AlarmScheduler.EXTRA_MANUAL, false) == true
        Logger.i("PlaybackSvc", "onStartCommand", mapOf(
            "scheduleId" to (scheduleId ?: "null"),
            "manual" to manual.toString(),
        ))

        startForeground(NOTIFICATION_ID, buildNotification("Starting playback…"))

        if (scheduleId == null) {
            Logger.e("PlaybackSvc", "No scheduleId; stopping")
            stopSelfSafe()
            return START_NOT_STICKY
        }
        currentJob?.cancel()
        currentJob = scope.launch { runFlow(scheduleId, manual) }
        return START_NOT_STICKY
    }

    private suspend fun runFlow(scheduleId: String, manual: Boolean) {
        acquireScreenWake()
        try {
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

            // Try up to 2 times. Many transient issues (slow network, modal
            // dialog, missed accessibility event) clear up on the second try.
            var success = false
            for (attempt in 1..2) {
                Logger.i("PlaybackSvc", "Launch attempt", mapOf(
                    "attempt" to attempt.toString(),
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

                launchYtMusic(choice)

                val playing = waitForPlayback(timeoutMs = 25_000)
                if (playing) {
                    success = true
                    repo.recordPlayed(schedule.id, choice.playlistId)
                    Logger.i("PlaybackSvc", "Playback verified", mapOf("attempt" to attempt.toString()))
                    updateNotification("Playing ${schedule.name}")
                    if (needsAccessibility) {
                        val done = YtmAccessibilityService.awaitActionComplete(20_000)
                        Logger.i("PlaybackSvc", "Post-launch action result", mapOf("completed" to done.toString()))
                    }
                    break
                } else {
                    Logger.w("PlaybackSvc", "Playback NOT detected", mapOf("attempt" to attempt.toString()))
                    if (attempt < 2) {
                        // Brief pause before retry, and clear any stale queued action.
                        delay(2000)
                    }
                }
            }
            if (!success) {
                postFailure("Playback didn't start for '${schedule.name}' after 2 attempts — see logs")
            }
        } finally {
            releaseScreenWake()
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

    private fun launchYtMusic(choice: PlaylistPicker.Choice) {
        val uri = Uri.parse("https://music.youtube.com/playlist?list=${choice.playlistId}&playnext=1")
        val launch = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(YT_MUSIC_PKG)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        Logger.i("PlaybackSvc", "Launching YT Music", mapOf(
            "uri" to uri.toString(),
            "package" to YT_MUSIC_PKG,
        ))
        // Route through KeyguardDismissActivity so screen wakes & lock dismisses
        val keyguardIntent = Intent(this, KeyguardDismissActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(KeyguardDismissActivity.EXTRA_LAUNCH, launch)
        }
        try {
            startActivity(keyguardIntent)
        } catch (t: Throwable) {
            Logger.e("PlaybackSvc", "Failed to start KeyguardDismissActivity", t = t)
            // Fallback: try launch directly
            runCatching { startActivity(launch) }
                .onFailure { t2 -> Logger.e("PlaybackSvc", "Direct launch also failed", t = t2) }
        }
    }

    private suspend fun waitForPlayback(timeoutMs: Long): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        var iter = 0
        while (System.currentTimeMillis() < deadline) {
            val playing = am?.isMusicActive == true
            if (iter % 5 == 0) {
                Logger.d("PlaybackSvc", "Playback poll", mapOf(
                    "isMusicActive" to playing.toString(),
                    "elapsedMs" to (timeoutMs - (deadline - System.currentTimeMillis())).toString(),
                ))
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
        val pm = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "YTMT:screenWake"
        )
        wl.setReferenceCounted(false)
        wl.acquire(60_000)
        wakeLock = wl
        Logger.i("PlaybackSvc", "Acquired screen wakelock")
    }

    private fun releaseScreenWake() {
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun postFailure(msg: String) {
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

        fun startManual(context: Context, scheduleId: String) {
            val intent = Intent(context, PlaybackTriggerService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(AlarmScheduler.EXTRA_MANUAL, true)
            }
            context.startForegroundService(intent)
        }
    }
}

// Suppress unused warnings for foregroundServiceType reference at compile time.
@Suppress("unused")
private val FGS_TYPES_REF = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
