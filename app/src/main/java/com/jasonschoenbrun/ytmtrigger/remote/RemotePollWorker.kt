package com.jasonschoenbrun.ytmtrigger.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jasonschoenbrun.ytmtrigger.log.Logger
import java.util.concurrent.TimeUnit

/**
 * Periodic remote sync.
 *
 * Firestore snapshot listeners only deliver while the process is alive, and
 * this app is normally killed between alarms, so a listener alone would mean
 * remote edits sat unapplied for hours. Polling gives a predictable ceiling
 * on how stale the device can be without needing FCM and a paid Cloud
 * Functions backend to push.
 *
 * 15 minutes is WorkManager's minimum period; combined with an opportunistic
 * sync on every app start, trigger and self-test, that is the practical
 * latency for a remote change or a "play now".
 */
class RemotePollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!RemoteGate.isReady(applicationContext)) return Result.success()
        val ok = RemoteSync.syncOnce(applicationContext, reason = "periodic")
        // Retrying immediately on a transient network blip is pointless when
        // the next poll is minutes away, so a failure just waits its turn.
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "remote-poll"

        fun ensureScheduled(context: Context) {
            if (!RemoteGate.isCompiledIn()) return
            val request = PeriodicWorkRequestBuilder<RemotePollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Logger.i("Remote", "Remote poll scheduled", mapOf("everyMin" to "15"))
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
