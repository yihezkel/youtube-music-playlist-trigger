package com.jasonschoenbrun.ytmtrigger.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

object AlarmScheduler {

    const val EXTRA_SCHEDULE_ID = "scheduleId"
    const val EXTRA_OCCURRENCE_MS = "occurrenceMs"
    const val EXTRA_MANUAL = "manual"
    /**
     * Set only after the user has explicitly confirmed a Shabat / Yom Tov
     * warning. Absent means "not confirmed", so any path that forgets to
     * forward it fails closed and nothing plays.
     */
    const val EXTRA_OVERRIDE_CALENDAR = "overrideCalendar"
    /** How long before a trigger the accessibility preflight runs. */
    const val PREFLIGHT_LEAD_MIN = 6L
    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun rescheduleAll(context: Context, schedules: List<Schedule>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Logger.w("Alarm", "Cannot schedule exact alarms - permission missing")
        }
        // C-fix-3: cancel previous before re-arming all so we never have two
        // pending intents for the same schedule (which could double-fire).
        for (s in schedules) cancel(context, s.id)
        for (s in schedules) if (s.enabled) scheduleNext(context, s)
    }

    fun scheduleNext(context: Context, schedule: Schedule) {
        if (!schedule.enabled) return
        if (schedule.daysOfWeek.isEmpty()) {
            Logger.w("Alarm", "Schedule has no days; not scheduling", mapOf("id" to schedule.id))
            return
        }
        val nextMs = computeNextTriggerMs(schedule) ?: run {
            Logger.w("Alarm", "Could not compute next trigger", mapOf("id" to schedule.id))
            return
        }
        // C-fix-3 (belt-and-braces): explicitly cancel any pre-existing
        // PendingIntent for this schedule before re-arming. rescheduleAll
        // already does this, but scheduleNext is also called on its own.
        cancel(context, schedule.id)
        val pi = pendingIntent(context, schedule.id, nextMs, create = true) ?: run {
            Logger.e("Alarm", "PendingIntent null", mapOf("id" to schedule.id))
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        schedulePreflight(context, schedule.id, nextMs)
        // H-fix-2: use setAlarmClock instead of setExactAndAllowWhileIdle.
        // The user-visible alarm-clock path is the highest-priority wakeup
        // available to apps and survives doze, restricted standby, and
        // foreground-service throttling. Speculative; eval-traced so we can
        // decide whether to keep it.
        EvalFix.start("H-fix-2-setAlarmClock", mapOf(
            "id" to schedule.id,
            "deltaMin" to ((nextMs - System.currentTimeMillis()) / 60000).toString(),
        ))
        try {
            val showIntent = PendingIntent.getActivity(
                context, schedule.id.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val info = AlarmManager.AlarmClockInfo(nextMs, showIntent)
            am.setAlarmClock(info, pi)
            EvalFix.end("H-fix-2-setAlarmClock", success = true)
            // E-fix-1: include daysOfWeek + targetTime + actual computed
            // trigger so we can verify the schedule fires on the correct day.
            Logger.i("Alarm", "Scheduled", mapOf(
                "id" to schedule.id,
                "name" to schedule.name,
                "at" to FMT.format(Date(nextMs)),
                "inMin" to ((nextMs - System.currentTimeMillis()) / 60000).toString(),
                "daysOfWeek" to schedule.daysOfWeek.joinToString(","),
                "localTime" to schedule.localTime().toString(),
            ))
        } catch (se: SecurityException) {
            EvalFix.end("H-fix-2-setAlarmClock", success = false, mapOf("err" to "SecurityException"))
            Logger.e("Alarm", "setAlarmClock denied", t = se)
            // Fall back to the previous behaviour so we still schedule something.
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMs, pi)
                Logger.w("Alarm", "Fell back to setExactAndAllowWhileIdle")
            } catch (se2: SecurityException) {
                Logger.e("Alarm", "Fallback also denied", t = se2)
            }
        } catch (t: Throwable) {
            EvalFix.end("H-fix-2-setAlarmClock", success = false, mapOf("err" to (t.message ?: "")))
            Logger.e("Alarm", "setAlarmClock failed", t = t)
        }
    }

    /**
     * Fire [scheduleId] as a manual trigger almost immediately.
     *
     * Deliberately goes through an exact alarm rather than calling
     * [android.content.Context.startForegroundService] directly: remote
     * commands are handled from a background worker, and since Android 12 a
     * background start throws `ForegroundServiceStartNotAllowedException`.
     * Delivering an exact alarm puts the app on the temporary power
     * allowlist, which is what makes the existing scheduled path legal — so
     * this reuses it instead of inventing a second, fragile one.
     */
    fun triggerSoon(context: Context, scheduleId: String, delayMs: Long = 2_000L) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = "com.jasonschoenbrun.ytmtrigger.TRIGGER"
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_MANUAL, true)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            "remote:$scheduleId".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = System.currentTimeMillis() + delayMs
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Logger.i("Alarm", "Immediate trigger armed", mapOf(
                "id" to scheduleId,
                "at" to FMT.format(Date(at)),
            ))
        } catch (se: SecurityException) {
            Logger.e("Alarm", "Immediate trigger denied", t = se)
        }
    }

    /**
     * Arm a wake-up [PREFLIGHT_LEAD_MIN] minutes before [triggerMs] so the
     * accessibility service can be verified, and repaired if necessary, while
     * there is still time to restart the process harmlessly.
     */
    private fun schedulePreflight(context: Context, scheduleId: String, triggerMs: Long) {
        val at = triggerMs - PREFLIGHT_LEAD_MIN * 60_000L
        if (at <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            "preflight:$scheduleId".hashCode(),
            Intent(context, PreflightReceiver::class.java).setAction(PreflightReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Logger.i("Alarm", "Preflight scheduled", mapOf(
                "id" to scheduleId,
                "at" to FMT.format(Date(at)),
                "leadMin" to PREFLIGHT_LEAD_MIN.toString(),
            ))
        } catch (se: SecurityException) {
            Logger.w("Alarm", "Preflight alarm denied", t = se)
        }
    }

    fun cancel(context: Context, scheduleId: String) {        val pi = pendingIntent(context, scheduleId, 0, create = false) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
        Logger.d("Alarm", "Cancelled", mapOf("id" to scheduleId))
    }

    private fun pendingIntent(context: Context, scheduleId: String, occurrenceMs: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, TriggerReceiver::class.java).apply {
            action = "com.jasonschoenbrun.ytmtrigger.TRIGGER"
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_OCCURRENCE_MS, occurrenceMs)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        val reqCode = scheduleId.hashCode()
        return PendingIntent.getBroadcast(context, reqCode, intent, flags)
    }

    /**
     * Minutes until the soonest enabled schedule fires, or null if none.
     */
    fun minutesToNextTrigger(schedules: List<Schedule>): Long? {
        val now = System.currentTimeMillis()
        return schedules.filter { it.enabled }
            .mapNotNull { computeNextTriggerMs(it) }
            .minOrNull()
            ?.let { (it - now) / 60000 }
    }

    fun computeNextTriggerMs(schedule: Schedule, now: LocalDateTime = LocalDateTime.now()): Long? {        val time = schedule.localTime()
        val days = schedule.daysOfWeek.map { DayOfWeek.of(it) }.toSet()
        if (days.isEmpty()) return null
        var probe: LocalDate = now.toLocalDate()
        for (i in 0..7) {
            val candidate: LocalDate = probe.plusDays(i.toLong())
            if (candidate.dayOfWeek !in days) continue
            val dt = LocalDateTime.of(candidate, time)
            if (dt.isAfter(now)) return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }

    /**
     * Every occurrence of [schedule] in the next [days] days, in order.
     *
     * Used by the UI to warn when a schedule would otherwise have fired during
     * Shabat or Yom Tov. Unlike [computeNextTriggerMs] this does not stop at
     * the first hit, because a weekly warning has to consider all of them.
     */
    fun occurrencesWithin(
        schedule: Schedule,
        days: Long,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<LocalDateTime> {
        val wanted = schedule.daysOfWeek.map { DayOfWeek.of(it) }.toSet()
        if (wanted.isEmpty()) return emptyList()
        val time = schedule.localTime()
        val end = now.plusDays(days)
        val out = mutableListOf<LocalDateTime>()
        var day = now.toLocalDate()
        while (true) {
            val at = LocalDateTime.of(day, time)
            if (at.isAfter(end)) break
            if (day.dayOfWeek in wanted && at.isAfter(now)) out += at
            day = day.plusDays(1)
        }
        return out
    }
}
