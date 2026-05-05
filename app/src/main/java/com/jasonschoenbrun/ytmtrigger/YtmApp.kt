package com.jasonschoenbrun.ytmtrigger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
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
    }

    companion object {
        const val CH_TRIGGER = "trigger"
        const val CH_FAILURE = "failure"

        @Volatile var instance: YtmApp? = null
            private set

        fun get(context: Context): YtmApp =
            (context.applicationContext as YtmApp).also { instance = it }
    }
}
