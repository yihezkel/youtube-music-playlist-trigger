package com.jasonschoenbrun.ytmtrigger.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.log.Logger
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
    private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun rescheduleAll(context: Context, schedules: List<Schedule>) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            Logger.w("Alarm", "Cannot schedule exact alarms - permission missing")
        }
        // Cancel previous before re-arming all
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
        val pi = pendingIntent(context, schedule.id, nextMs, create = true) ?: run {
            Logger.e("Alarm", "PendingIntent null", mapOf("id" to schedule.id))
            return
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMs, pi)
            Logger.i("Alarm", "Scheduled", mapOf(
                "id" to schedule.id,
                "name" to schedule.name,
                "at" to FMT.format(Date(nextMs)),
                "inMin" to ((nextMs - System.currentTimeMillis()) / 60000).toString(),
            ))
        } catch (se: SecurityException) {
            Logger.e("Alarm", "Exact alarm denied", t = se)
        }
    }

    fun cancel(context: Context, scheduleId: String) {
        val pi = pendingIntent(context, scheduleId, 0, create = false) ?: return
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

    fun computeNextTriggerMs(schedule: Schedule, now: LocalDateTime = LocalDateTime.now()): Long? {
        val time = schedule.localTime()
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
}
