package com.jasonschoenbrun.ytmtrigger.selftest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schedules a periodic self-test. We don't use [AlarmManager.setRepeating]
 * because on modern Android it is inexact and can be coalesced indefinitely.
 * Instead we set a single [AlarmManager.setAlarmClock] (matching the user-
 * visible alarm-clock channel that survives doze) and the receiver re-arms
 * itself after each invocation.
 */
object SelfTestScheduler {

    /** Period between self-tests. */
    const val PERIOD_MS: Long = 6L * 60 * 60 * 1000
    /** Initial delay used the very first time we ever schedule. */
    const val INITIAL_DELAY_MS: Long = 30L * 60 * 1000

    private const val REQ_CODE = 909_001
    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Schedule the next self-test [delayMs] from now. If [delayMs] is null
     * uses [PERIOD_MS] for a normal re-arm.
     */
    fun scheduleNext(context: Context, delayMs: Long = PERIOD_MS) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(60_000L)
        val pi = pendingIntent(context, create = true) ?: return
        val showIntent = PendingIntent.getActivity(
            context, REQ_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pi)
            Logger.i("SelfTestSched", "Scheduled", mapOf(
                "at" to FMT.format(Date(triggerAt)),
                "inMin" to (delayMs / 60_000).toString(),
            ))
        } catch (se: SecurityException) {
            Logger.w("SelfTestSched", "setAlarmClock denied, falling back", t = se)
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                Logger.i("SelfTestSched", "Scheduled (fallback)", mapOf("at" to FMT.format(Date(triggerAt))))
            } catch (t: Throwable) {
                Logger.e("SelfTestSched", "Fallback also failed", t = t)
            }
        } catch (t: Throwable) {
            Logger.e("SelfTestSched", "setAlarmClock failed", t = t)
        }
    }

    /**
     * Ensure a self-test is scheduled if [enabled] is true; cancel any
     * existing one if [enabled] is false. Safe to call from app startup —
     * if a self-test is already armed, this call is a no-op (re-arming
     * with the same period is fine).
     */
    fun ensureScheduled(context: Context, enabled: Boolean) {
        if (!enabled) {
            cancel(context)
            return
        }
        // If no existing PendingIntent, use the short initial delay; otherwise
        // leave the existing one alone unless we're forcing a refresh.
        val existing = pendingIntent(context, create = false)
        if (existing != null) {
            Logger.d("SelfTestSched", "Already scheduled; not re-arming")
            return
        }
        scheduleNext(context, INITIAL_DELAY_MS)
    }

    fun cancel(context: Context) {
        val pi = pendingIntent(context, create = false) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
        Logger.i("SelfTestSched", "Cancelled")
    }

    private fun pendingIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, SelfTestReceiver::class.java).apply {
            action = ACTION_FIRE
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQ_CODE, intent, flags)
    }

    const val ACTION_FIRE = "com.jasonschoenbrun.ytmtrigger.SELFTEST_FIRE"
}
