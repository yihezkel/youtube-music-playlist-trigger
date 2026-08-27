package com.jasonschoenbrun.ytmtrigger.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.calendar.HebrewCalendarChecker
import com.jasonschoenbrun.ytmtrigger.calendar.calendarConfig
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
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
    const val ACTION_STOP = "com.jasonschoenbrun.ytmtrigger.STOP"
    const val ACTION_SHABAT_PREP = "com.jasonschoenbrun.ytmtrigger.SHABAT_PREP"
    /** How long before Shabat / Yom Tov the phone is stopped and muted. */
    const val SHABAT_PREP_LEAD_MIN = 15L
    /** How long before a trigger the accessibility preflight runs. */
    const val PREFLIGHT_LEAD_MIN = 6L
    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Serialises [rescheduleAll].
     *
     * The body cancels every alarm and then re-arms them, which is only safe
     * if one pass finishes before the next starts. It does not: on every app
     * start three passes run within about 200 ms of each other - [YtmApp]'s
     * startup re-arm, plus BootReceiver handling `LOCKED_BOOT_COMPLETED` and
     * `BOOT_COMPLETED`, which Android re-delivers whenever the app leaves the
     * stopped state (148 such deliveries in 15 days on a phone that had been
     * up for five). A remote config sync adds more. The device log shows two
     * passes inside their arming loops at once - two `Scheduled` lines for one
     * id with no `Cancelled` between them - so a cancel from one pass can land
     * after another has already armed that id, leaving it cancelled and the
     * block silently not firing. This is almost certainly the "stale re-arm
     * race after a config sync" that was being worked around by restarting the
     * app twice.
     *
     * Serialising is enough: each pass is idempotent on its own, so three in a
     * row simply converge. It deliberately does not try to skip the redundant
     * passes, which is an optimisation rather than the correctness fix.
     */
    private val rescheduleLock = Any()

    fun rescheduleAll(context: Context, schedules: List<Schedule>) = synchronized(rescheduleLock) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Logger.w("Alarm", "Cannot schedule exact alarms - permission missing")
        }
        // C-fix-3: cancel previous before re-arming all so we never have two
        // pending intents for the same schedule (which could double-fire).
        for (s in schedules) { cancel(context, s.id); if (!s.enabled) cancelStop(context, s.id) }
        // Per-schedule, because the cancel loop above has already run: a single
        // schedule that throws here used to abort the whole pass and leave every
        // remaining alarm cancelled and never re-armed - every later block
        // silently missed, with nothing but one stack trace in the log to show
        // for it. Seen for real when a malformed timeMinutes of 1444 reached
        // Schedule.localTime() and threw DateTimeException mid-loop.
        for (s in schedules) {
            if (!s.enabled) continue
            try {
                scheduleNext(context, s)
            } catch (t: Throwable) {
                Logger.e("Alarm", "Could not arm a schedule; carrying on with the rest", mapOf(
                    "id" to s.id, "name" to s.name,
                    "timeMinutes" to s.timeMinutes.toString(),
                ), t = t)
            }
        }
        // Independent of any schedule, but this is the one place every caller
        // already goes through: boot, app start, edits and remote config.
        scheduleShabatPrep(context)
    }

    /**
     * Arm the pre-Shabat / pre-Yom Tov mute, [SHABAT_PREP_LEAD_MIN] minutes
     * before the window opens.
     *
     * @param fromMs look for the first window starting after this instant.
     *   Callers that have just handled a window must pass a time beyond it.
     */
    fun scheduleShabatPrep(context: Context, fromMs: Long = System.currentTimeMillis()) {
        val cfg = SettingsRepository.get(context).current().calendarConfig()
        val from = Instant.ofEpochMilli(fromMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val next = HebrewCalendarChecker.nextWindowStart(from, cfg) ?: run {
            Logger.w("Alarm", "No upcoming Shabat/Yom Tov window found")
            return
        }
        val at = next.first.minusMinutes(SHABAT_PREP_LEAD_MIN)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            context,
            "shabatPrep".hashCode(),
            Intent(context, ShabatPrepReceiver::class.java)
                .setAction(ACTION_SHABAT_PREP)
                .putExtra(ShabatPrepReceiver.EXTRA_WHAT, next.second),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            // A time already in the past fires straight away, which is what we
            // want if the app starts inside the lead-in.
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Logger.i("Alarm", "Shabat prep scheduled", mapOf(
                "what" to next.second,
                "windowStart" to FMT.format(Date(
                    next.first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())),
                "muteAt" to FMT.format(Date(at)),
                "leadMin" to SHABAT_PREP_LEAD_MIN.toString(),
            ))
        } catch (se: SecurityException) {
            Logger.w("Alarm", "Shabat prep alarm denied", t = se)
        }
    }

    fun scheduleNext(context: Context, schedule: Schedule) {
        if (!schedule.enabled) return
        if (schedule.startsAfter != null) {
            Logger.i("Alarm", "Not clock-armed; follows another block", mapOf(
                "id" to schedule.id, "startsAfter" to schedule.startsAfter,
            ))
            return
        }
        if (schedule.daysOfWeek.isEmpty()) {
            Logger.w("Alarm", "Schedule has no days; not scheduling", mapOf("id" to schedule.id))
            return
        }
        val nextMs = computeNextTriggerMs(context, schedule) ?: run {
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
                "localTime" to ScheduleTimes.describe(schedule),
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

    /**
     * Arm this schedule's stop time, if it has one.
     *
     * Called when playback actually starts rather than from [scheduleNext],
     * because [scheduleNext] runs again the moment a trigger fires: computing
     * the stop from the *next* occurrence would immediately cancel the stop
     * belonging to the playback that just began.
     *
     * A stop time at or before the start time means the next day, so an
     * overnight schedule stops in the morning rather than never.
     */
    fun scheduleStop(context: Context, schedule: Schedule) {
        cancelStop(context, schedule.id)
        AutoStop.clear(context, schedule.id)
        val stopTime = schedule.stopLocalTime()
        val at: Long
        val how: String
        if (stopTime != null) {
            val now = LocalDateTime.now()
            var stop = LocalDateTime.of(now.toLocalDate(), stopTime)
            if (!stop.isAfter(now)) stop = stop.plusDays(1)
            at = stop.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            how = "clock"
        } else {
            // Stop after a fixed run instead. Measured from now, which is the
            // moment the block starts - this is only called on its first item.
            // It exists for blocks whose start moves through the year: a fixed
            // clock stop gives those a different length every week.
            val mins = schedule.autoStopMinutes ?: return
            at = System.currentTimeMillis() + mins * 60_000L
            how = "after ${mins}m"
            AutoStop.record(context, schedule.id, at)
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, stopPendingIntent(context, schedule.id))
            Logger.i("Alarm", "Stop scheduled", mapOf(
                "id" to schedule.id,
                "name" to schedule.name,
                "how" to how,
                "at" to FMT.format(Date(at)),
                "inMin" to ((at - System.currentTimeMillis()) / 60000).toString(),
            ))
        } catch (se: SecurityException) {
            Logger.w("Alarm", "Stop alarm denied", t = se)
        }
    }

    fun cancelStop(context: Context, scheduleId: String) {
        val pi = stopPendingIntent(context, scheduleId)
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
    }

    private fun stopPendingIntent(context: Context, scheduleId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "stop:$scheduleId".hashCode(),
            Intent(context, StopReceiver::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

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
    fun minutesToNextTrigger(context: Context, schedules: List<Schedule>): Long? {
        val now = System.currentTimeMillis()
        return schedules.filter { it.enabled }
            .mapNotNull { computeNextTriggerMs(context, it) }
            .minOrNull()
            ?.let { (it - now) / 60000 }
    }

    fun computeNextTriggerMs(
        context: Context,
        schedule: Schedule,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long? {
        if (schedule.startsAfter != null) return null
        val days = schedule.daysOfWeek.map { DayOfWeek.of(it) }.toSet()
        if (days.isEmpty()) return null
        val cfg = SettingsRepository.get(context).current().calendarConfig()
        val today: LocalDate = now.toLocalDate()
        // Anchored schedules can skip a ticked day entirely (no sunset, or no
        // window ending), so this must keep looking rather than give up on the
        // first matching weekday. A fortnight covers the longest realistic gap
        // between consecutive Shabat/Yom Tov endings.
        for (i in 0..14L) {
            val candidate: LocalDate = today.plusDays(i)
            if (candidate.dayOfWeek !in days) continue
            val dt = ScheduleTimes.triggerOn(schedule, candidate, cfg) ?: continue
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
        context: Context,
        schedule: Schedule,
        days: Long,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<LocalDateTime> {
        if (schedule.startsAfter != null) return emptyList()
        val wanted = schedule.daysOfWeek.map { DayOfWeek.of(it) }.toSet()
        if (wanted.isEmpty()) return emptyList()
        val cfg = SettingsRepository.get(context).current().calendarConfig()
        val end = now.plusDays(days)
        val out = mutableListOf<LocalDateTime>()
        var day = now.toLocalDate()
        // Walk one extra day: an anchored time can land after midnight, so the
        // occurrence for the last in-range date may still be computed from it.
        val lastDay = end.toLocalDate().plusDays(1)
        while (!day.isAfter(lastDay)) {
            if (day.dayOfWeek in wanted) {
                val at = ScheduleTimes.triggerOn(schedule, day, cfg)
                if (at != null && at.isAfter(now) && !at.isAfter(end)) out += at
            }
            day = day.plusDays(1)
        }
        return out.sorted()
    }
}
