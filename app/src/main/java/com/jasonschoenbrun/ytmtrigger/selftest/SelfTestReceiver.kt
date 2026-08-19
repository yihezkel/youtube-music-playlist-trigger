package com.jasonschoenbrun.ytmtrigger.selftest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.diag.RunOutcome
import com.jasonschoenbrun.ytmtrigger.diag.SelfTestRunRecord
import com.jasonschoenbrun.ytmtrigger.diag.SelfTestRunStore
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.remote.RemoteSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the self-test alarm and invokes [SelfTestRunner]. Persists a
 * full [SelfTestRunRecord] via [SelfTestRunStore] for forensic drill-down,
 * AND updates [SettingsRepository] last-success / last-failure / last-skip
 * fields so the existing UI summary keeps working. Re-arms the scheduler
 * for the next period regardless of outcome.
 *
 * The actual self-test work uses [goAsync] to keep the receiver alive while
 * the suspending [SelfTestRunner.run] executes (which can take up to ~75s in
 * the worst case: three 25s strategies).
 */
class SelfTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SelfTestScheduler.ACTION_FIRE &&
            intent.action != ACTION_MANUAL) {
            return
        }
        val manual = intent.action == ACTION_MANUAL
        Logger.i("SelfTestRecv", "Fired", mapOf("manual" to manual.toString()))
        // Always re-arm so the cadence continues even if this run aborts.
        SelfTestScheduler.scheduleNext(context)

        val repo = SettingsRepository.get(context)
        val settings = repo.current()
        if (!settings.selfTestEnabled && !manual) {
            Logger.i("SelfTestRecv", "Self-test disabled in settings")
            return
        }

        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            var needsUpload = false
            try {
                val trigger = if (manual) SelfTestRunner.Trigger.Manual else SelfTestRunner.Trigger.Scheduled
                val record = SelfTestRunner.run(context.applicationContext, trigger = trigger)
                SelfTestRunStore.record(context.applicationContext, record)
                handleRecord(context.applicationContext, record, repo)
                needsUpload = record.outcome is RunOutcome.AllFailed
            } catch (t: Throwable) {
                Logger.e("SelfTestRecv", "Self-test crashed", t = t)
                repo.update {
                    it.copy(
                        lastSelfTestFailureMs = System.currentTimeMillis(),
                        lastSelfTestFailureReason = "Crash: ${t.message ?: t::class.simpleName}",
                    )
                }
                needsUpload = true
            } finally {
                // Runs for crashes too, not just AllFailed. A crash is the case
                // most in need of remote diagnosis, and leaving the push inside
                // the try meant it was the one outcome that published nothing.
                try {
                    if (needsUpload) RemoteSync.uploadLogs(context.applicationContext, days = 2)
                    RemoteSync.syncOnce(context.applicationContext, reason = "self-test")
                } catch (t: Throwable) {
                    Logger.w("SelfTestRecv", "Remote push after self-test failed", t = t)
                }
                pending.finish()
            }
        }
    }

    /** Translate the structured [SelfTestRunRecord.outcome] into the existing
     *  flat last-X settings fields, and start the audible alert on AllFailed. */
    private fun handleRecord(
        context: Context,
        record: SelfTestRunRecord,
        repo: SettingsRepository,
    ) {
        when (val o = record.outcome) {
            is RunOutcome.Skipped -> repo.update {
                it.copy(
                    lastSelfTestSkipMs = System.currentTimeMillis(),
                    lastSelfTestSkipReason = o.reason,
                )
            }
            is RunOutcome.Success -> repo.update {
                it.copy(
                    lastSelfTestSuccessMs = System.currentTimeMillis(),
                    lastSelfTestSuccessStrategy = o.strategy,
                )
            }
            is RunOutcome.AllFailed -> {
                val reason = o.summary + " (tried: ${o.tried.joinToString(",")})"
                repo.update {
                    it.copy(
                        lastSelfTestFailureMs = System.currentTimeMillis(),
                        lastSelfTestFailureReason = reason,
                    )
                }
                Logger.w("SelfTestRecv", "All strategies failed, starting alert", mapOf(
                    "reason" to reason, "runId" to record.runId,
                ))
                SelfTestAlertService.start(context)
            }
            is RunOutcome.Crash -> repo.update {
                it.copy(
                    lastSelfTestFailureMs = System.currentTimeMillis(),
                    lastSelfTestFailureReason = "Crash: ${o.message}",
                )
            }
            is RunOutcome.ConfigError -> repo.update {
                it.copy(
                    lastSelfTestFailureMs = System.currentTimeMillis(),
                    lastSelfTestFailureReason = o.message,
                )
            }
        }
    }

    companion object {
        const val ACTION_MANUAL = "com.jasonschoenbrun.ytmtrigger.SELFTEST_MANUAL"

        /** Convenience for "Run self-test now" UI button. */
        fun fireManual(context: Context) {
            val i = Intent(context, SelfTestReceiver::class.java).setAction(ACTION_MANUAL)
            context.sendBroadcast(i)
        }
    }
}
