package com.jasonschoenbrun.ytmtrigger.alarm

import com.jasonschoenbrun.ytmtrigger.calendar.HebrewCalendarChecker
import com.jasonschoenbrun.ytmtrigger.calendar.SolarCalculator
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.TimeAnchor
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Turns a [Schedule] plus a calendar date into the instant it should fire.
 *
 * Split out of [AlarmScheduler] because both the next-trigger search and the
 * week-ahead occurrence list need identical anchor maths; having one function
 * removes the risk of the alarm firing at one time while the Shabat warning
 * reasons about another.
 *
 * Returning null means "this schedule has no occurrence on that date", which
 * is a normal answer for [TimeAnchor.ShabatYomTovEnd] on an ordinary weekday.
 */
object ScheduleTimes {

    fun triggerOn(
        schedule: Schedule,
        date: LocalDate,
        cfg: HebrewCalendarChecker.Config,
    ): LocalDateTime? {
        val offset = schedule.anchorOffsetMinutes.toLong()
        return when (schedule.timeAnchor) {
            TimeAnchor.FixedClock -> LocalDateTime.of(date, schedule.localTime())

            // Null at polar latitudes, where there may be no sunset at all.
            // Silently skipping the day is right: there is no sensible time to
            // substitute, and the fixed-clock fallback used for the Shabat
            // block would be a different, wrong, answer here.
            TimeAnchor.Sunset ->
                SolarCalculator.sunset(date, cfg.latitude, cfg.longitude)?.plusMinutes(offset)

            TimeAnchor.ShabatYomTovEnd ->
                HebrewCalendarChecker.windowEndOn(date, cfg)?.plusMinutes(offset)
        }
    }

    /** Human-readable summary of the anchor, for the schedule list and logs. */
    fun describe(schedule: Schedule): String {
        val off = schedule.anchorOffsetMinutes
        val shift = when {
            off > 0 -> " +${off}m"
            off < 0 -> " ${off}m"
            else -> ""
        }
        return when (schedule.timeAnchor) {
            TimeAnchor.FixedClock ->
                "%02d:%02d".format(schedule.timeMinutes / 60, schedule.timeMinutes % 60)
            TimeAnchor.Sunset -> "Sunset$shift"
            TimeAnchor.ShabatYomTovEnd -> "Shabat/Yom Tov ends$shift"
        }
    }
}
