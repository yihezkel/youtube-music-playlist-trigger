package com.jasonschoenbrun.ytmtrigger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.NotifListenerEnforcer
import com.jasonschoenbrun.ytmtrigger.remote.RemotePollWorker
import com.jasonschoenbrun.ytmtrigger.remote.RemoteSync
import com.jasonschoenbrun.ytmtrigger.selftest.SelfTestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YtmApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.init(this)
        Logger.i("App", "Application onCreate", mapOf(
            "versionName" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"),
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "model" to Build.MODEL,
        ))
        ensureChannels()
        // Auto-heal the A11y service if Android has disabled it (requires the
        // user to have granted WRITE_SECURE_SETTINGS via adb once; see
        // A11yPermissionEnforcer for details). Also installs an observer that
        // re-asserts immediately if anything flips it off while we're alive.
        try {
            A11yPermissionEnforcer.ensureEnabled(this)
            A11yPermissionEnforcer.startWatching(this)
        } catch (t: Throwable) {
            Logger.e("App", "A11y enforcer setup failed", t = t)
        }
        // NOTE: an earlier attempt restarted the process on every package
        // replace, on the theory that updates leave the accessibility service
        // bound but inert. That theory did not survive testing — see
        // A11yPermissionEnforcer.restartAfterDeadRun — so recovery is now
        // driven by observed failure rather than by a guess about its cause.
        // Same treatment for the notification listener, which is what lets
        // MediaSessionProbe see whether YT Music is really playing. Unlike
        // accessibility this cannot be self-granted, so we only record the
        // state and surface the one-time command when it's missing.
        try {
            NotifListenerEnforcer.logState(this)
        } catch (t: Throwable) {
            Logger.e("App", "Notification-listener state check failed", t = t)
        }
        // Remote control (optional). Everything here no-ops when Firebase
        // isn't configured or nobody has signed in.
        try {
            RemotePollWorker.ensureScheduled(this)
            appScope.launch { RemoteSync.syncOnce(this@YtmApp, reason = "app-start") }
        } catch (t: Throwable) {
            Logger.e("App", "Remote sync setup failed", t = t)
        }
        // Instrumentation for the intermittent dead-binding fault: sample
        // accessibility liveness every 15 minutes so the moment it stops is
        // recorded, instead of only being discovered by a six-hourly self-test.
        try {
            com.jasonschoenbrun.ytmtrigger.accessibility.A11yHealthWorker.ensureScheduled(this)
        } catch (t: Throwable) {
            Logger.e("App", "A11y health worker setup failed", t = t)
        }
        // Re-arm all schedules at app start (covers update-triggered restarts)
        appScope.launch {
            try {
                val repo = ScheduleRepository.get(this@YtmApp)
                val schedules = repo.flow.value
                AlarmScheduler.rescheduleAll(this@YtmApp, schedules)
                Logger.i("App", "Re-armed alarms on startup", mapOf("count" to schedules.count { it.enabled }.toString()))
            } catch (t: Throwable) {
                Logger.e("App", "Startup re-arm failed", t = t)
            }
        }
        // Self-test scheduler: arms a 6-hour-cadence alarm. Default-enabled
        // per spec; user can toggle it off in Settings.
        appScope.launch {
            try {
                val s = SettingsRepository.get(this@YtmApp).current()
                SelfTestScheduler.ensureScheduled(this@YtmApp, s.selfTestEnabled)
                Logger.i("App", "Self-test scheduler ensured", mapOf("enabled" to s.selfTestEnabled.toString()))
            } catch (t: Throwable) {
                Logger.e("App", "Self-test schedule failed", t = t)
            }
        }
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CH_TRIGGER,
                "Playlist trigger",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Foreground notification while a scheduled playback is starting."
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_FAILURE,
                "Failures",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Posted when a scheduled playback could not be verified."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_SELFTEST,
                "Self-test",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Background self-test status (silent unless it fails)."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_SELFTEST_ALERT,
                "Self-test alert",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Posted when the self-test detects YouTube Music is broken."
                setBypassDnd(true)
            }
        )
    }

    companion object {
        const val CH_TRIGGER = "trigger"
        const val CH_FAILURE = "failure"
        const val CH_SELFTEST = "selftest"
        const val CH_SELFTEST_ALERT = "selftest_alert"

        @Volatile var instance: YtmApp? = null
            private set

        fun get(context: Context): YtmApp =
            (context.applicationContext as YtmApp).also { instance = it }
    }
}
