package com.jasonschoenbrun.ytmtrigger.calendar

import com.jasonschoenbrun.ytmtrigger.log.Logger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Build the calendar config from stored settings, in one place so no caller
 *  can accidentally use a different location or offsets. */
fun com.jasonschoenbrun.ytmtrigger.data.AppSettings.calendarConfig(): HebrewCalendarChecker.Config =
    HebrewCalendarChecker.Config(
        israeliObservance = israeliObservance,
        latitude = latitude,
        longitude = longitude,
        startOffsetMin = shabatStartOffsetMin,
        endOffsetMin = shabatEndOffsetMin,
    )

/**
 * Decides when playback and the self-test must stay silent: Shabat and Yom Tov.
 *
 * This lives outside the `selftest` package on purpose. It used to sit there,
 * which made it look like a self-test concern and hid the fact that scheduled
 * playback had no calendar gate at all.
 *
 * Day windows are anchored to real sunset at the configured location rather
 * than to fixed clock times, because sunset in Israel moves by over three
 * hours across the year. Shabat runs from [Config.startOffsetMin] before
 * sunset on Friday to [Config.endOffsetMin] after sunset on Saturday; Yom Tov
 * windows span sunset of the day before to nightfall of the last day.
 *
 * Date table covers 2026-2030. After that the windows stop matching unless the
 * table is updated (a fall-open default per user request: "if anything is
 * unexpected, log it" - logging will reveal this).
 */
object HebrewCalendarChecker {

    /**
     * Everything the windows depend on. Bundled so callers cannot pass the
     * observance flag and forget the location, which would silently fall back
     * to a different sunset.
     */
    data class Config(
        val israeliObservance: Boolean,
        val latitude: Double,
        val longitude: Double,
        /** Minutes before sunset that Shabat / Yom Tov begins. */
        val startOffsetMin: Int,
        /** Minutes after sunset that it ends (nightfall). */
        val endOffsetMin: Int,
    )

    /**
     * Used only when sunset cannot be computed at all (polar latitudes). Wide
     * on purpose: over-blocking is the safe direction.
     */
    private val FALLBACK_START: LocalTime = LocalTime.of(16, 0)
    private val FALLBACK_END: LocalTime = LocalTime.of(21, 30)

    data class Window(val name: String, val startDay: LocalDate, val endDay: LocalDate)

    /** Result for callers; includes the matching window name when applicable. */
    data class Result(val skip: Boolean, val reason: String?)

    /** When the block begins on [day]: that day's sunset less the offset. */
    fun startOf(day: LocalDate, cfg: Config): LocalDateTime =
        SolarCalculator.sunset(day, cfg.latitude, cfg.longitude)
            ?.minusMinutes(cfg.startOffsetMin.toLong())
            ?: LocalDateTime.of(day, FALLBACK_START)

    /** When the block ends on [day]: that day's sunset plus the offset. */
    fun endOf(day: LocalDate, cfg: Config): LocalDateTime =
        SolarCalculator.sunset(day, cfg.latitude, cfg.longitude)
            ?.plusMinutes(cfg.endOffsetMin.toLong())
            ?: LocalDateTime.of(day, FALLBACK_END)

    /**
     * Pure evaluation with no logging, safe to call in a loop (the schedule
     * warning tests every occurrence in the coming week on each recomposition).
     */
    fun evaluate(at: LocalDateTime, cfg: Config): Result {
        val day = at.toLocalDate()
        // Shabat: sunset Friday -> nightfall Saturday.
        if (at.dayOfWeek == DayOfWeek.FRIDAY && !at.isBefore(startOf(day, cfg))) {
            return Result(true, "Shabat (Friday evening)")
        }
        if (at.dayOfWeek == DayOfWeek.SATURDAY && !at.isAfter(endOf(day, cfg))) {
            return Result(true, "Shabat (Saturday)")
        }
        // Yom Tov: sunset of the day before -> nightfall of the last day.
        val table = if (cfg.israeliObservance) YOM_TOV_ISRAEL else YOM_TOV_DIASPORA
        val hit = table.firstOrNull {
            !at.isBefore(startOf(it.startDay, cfg)) && !at.isAfter(endOf(it.endDay, cfg))
        }
        if (hit != null) return Result(true, "Yom Tov: ${hit.name}")
        return Result(false, null)
    }

    /** [evaluate], plus a warning when the hardcoded table has run out. */
    fun check(now: LocalDateTime, cfg: Config): Result {
        val result = evaluate(now, cfg)
        if (!result.skip) {
            val maxYear = (if (cfg.israeliObservance) YOM_TOV_ISRAEL else YOM_TOV_DIASPORA)
                .maxOf { it.endDay.year }
            if (now.year > maxYear) {
                Logger.w("HebrewCal", "Yom Tov table exhausted; please update YOM_TOV_*", mapOf(
                    "currentYear" to now.year.toString(),
                    "tableMaxYear" to maxYear.toString(),
                    "israeliObservance" to cfg.israeliObservance.toString(),
                ))
            }
        }
        return result
    }

    /**
     * Which of [occurrences] land inside a Shabat / Yom Tov window.
     *
     * Takes already-computed occurrences rather than a [com.jasonschoenbrun.ytmtrigger.data.Schedule]
     * so this object never needs to know how schedules recur, and so there is
     * no dependency cycle with the alarm package.
     */
    fun blockedOccurrences(
        occurrences: List<LocalDateTime>,
        cfg: Config,
    ): List<Pair<LocalDateTime, String>> = occurrences.mapNotNull { at ->
        val r = evaluate(at, cfg)
        if (r.skip) at to (r.reason ?: "Shabat/Yom Tov") else null
    }

    /**
     * Nightfall on [day] if a Shabat or Yom Tov window genuinely ends then,
     * else null.
     *
     * "Genuinely" matters when one window runs straight into the next - Shabat
     * followed by Yom Tov on Saturday night, or the middle of a two-day
     * festival. Saturday's nightfall is not an end in that case, so this
     * re-checks a moment past the candidate and rejects it if the block is
     * still in force. Without that guard a "after Shabat" schedule would fire
     * in the middle of Yom Tov.
     */
    fun windowEndOn(day: LocalDate, cfg: Config): LocalDateTime? {
        val table = if (cfg.israeliObservance) YOM_TOV_ISRAEL else YOM_TOV_DIASPORA
        val ends = day.dayOfWeek == DayOfWeek.SATURDAY || table.any { it.endDay == day }
        if (!ends) return null
        val end = endOf(day, cfg)
        return if (evaluate(end.plusMinutes(1), cfg).skip) null else end
    }

    /** The coming Shabat window, for display in settings. */
    fun nextShabatWindow(from: LocalDate, cfg: Config): Pair<LocalDateTime, LocalDateTime> {
        var friday = from
        while (friday.dayOfWeek != DayOfWeek.FRIDAY) friday = friday.plusDays(1)
        return startOf(friday, cfg) to endOf(friday.plusDays(1), cfg)
    }

    /**
     * When the next Shabat or Yom Tov window begins strictly after [from], and
     * what it is. Null only if the Yom Tov table has run out and somehow no
     * Friday follows.
     *
     * Used to mute the phone shortly before the window opens, so callers must
     * pass a [from] beyond a window they have already handled or they will
     * simply be handed the same one again.
     */
    fun nextWindowStart(from: LocalDateTime, cfg: Config): Pair<LocalDateTime, String>? {
        val candidates = mutableListOf<Pair<LocalDateTime, String>>()
        val today = from.toLocalDate()
        for (i in 0..7L) {
            val day = today.plusDays(i)
            if (day.dayOfWeek != DayOfWeek.FRIDAY) continue
            val start = startOf(day, cfg)
            if (start.isAfter(from)) { candidates += start to "Shabat"; break }
        }
        val table = if (cfg.israeliObservance) YOM_TOV_ISRAEL else YOM_TOV_DIASPORA
        table.map { startOf(it.startDay, cfg) to it.name }
            .filter { it.first.isAfter(from) }
            .minByOrNull { it.first }
            ?.let { candidates += it }
        return candidates.minByOrNull { it.first }
    }

    // Diaspora Yom Tov windows (2026-2030). Each row = [start day, end day].
    // Two-day Yom Tov for Pesach 1-2 and 7-8, Shavuot, Rosh Hashanah, Sukkot 1-2,
    // and Shmini Atzeret/Simchat Torah. Yom Kippur is one day.
    private val YOM_TOV_DIASPORA: List<Window> = listOf(
        // --- 2026 (5786 spring + 5787 autumn) ---
        Window("Pesach 1-2 5786",  date(2026, 4, 1),  date(2026, 4, 3)),
        Window("Pesach 7-8 5786",  date(2026, 4, 7),  date(2026, 4, 9)),
        Window("Shavuot 5786",     date(2026, 5, 21), date(2026, 5, 23)),
        Window("Rosh Hashanah 5787", date(2026, 9, 11), date(2026, 9, 13)),
        Window("Yom Kippur 5787",  date(2026, 9, 20), date(2026, 9, 21)),
        Window("Sukkot 1-2 5787",  date(2026, 9, 25), date(2026, 9, 27)),
        Window("Shmini Atzeret/Simchat Torah 5787", date(2026, 10, 2), date(2026, 10, 4)),
        // --- 2027 (5787 spring + 5788 autumn) ---
        Window("Pesach 1-2 5787",  date(2027, 4, 21), date(2027, 4, 23)),
        Window("Pesach 7-8 5787",  date(2027, 4, 27), date(2027, 4, 29)),
        Window("Shavuot 5787",     date(2027, 6, 10), date(2027, 6, 12)),
        Window("Rosh Hashanah 5788", date(2027, 10, 1), date(2027, 10, 3)),
        Window("Yom Kippur 5788",  date(2027, 10, 10), date(2027, 10, 11)),
        Window("Sukkot 1-2 5788",  date(2027, 10, 15), date(2027, 10, 17)),
        Window("Shmini Atzeret/Simchat Torah 5788", date(2027, 10, 22), date(2027, 10, 24)),
        // --- 2028 (5788 spring + 5789 autumn) ---
        Window("Pesach 1-2 5788",  date(2028, 4, 10), date(2028, 4, 12)),
        Window("Pesach 7-8 5788",  date(2028, 4, 16), date(2028, 4, 18)),
        Window("Shavuot 5788",     date(2028, 5, 30), date(2028, 6, 1)),
        Window("Rosh Hashanah 5789", date(2028, 9, 20), date(2028, 9, 22)),
        Window("Yom Kippur 5789",  date(2028, 9, 29), date(2028, 9, 30)),
        Window("Sukkot 1-2 5789",  date(2028, 10, 4), date(2028, 10, 6)),
        Window("Shmini Atzeret/Simchat Torah 5789", date(2028, 10, 11), date(2028, 10, 13)),
        // --- 2029 (5789 spring + 5790 autumn) ---
        Window("Pesach 1-2 5789",  date(2029, 3, 30), date(2029, 4, 1)),
        Window("Pesach 7-8 5789",  date(2029, 4, 5),  date(2029, 4, 7)),
        Window("Shavuot 5789",     date(2029, 5, 19), date(2029, 5, 21)),
        Window("Rosh Hashanah 5790", date(2029, 9, 9), date(2029, 9, 11)),
        Window("Yom Kippur 5790",  date(2029, 9, 18), date(2029, 9, 19)),
        Window("Sukkot 1-2 5790",  date(2029, 9, 23), date(2029, 9, 25)),
        Window("Shmini Atzeret/Simchat Torah 5790", date(2029, 9, 30), date(2029, 10, 2)),
        // --- 2030 (5790 spring + 5791 autumn) ---
        Window("Pesach 1-2 5790",  date(2030, 4, 17), date(2030, 4, 19)),
        Window("Pesach 7-8 5790",  date(2030, 4, 23), date(2030, 4, 25)),
        Window("Shavuot 5790",     date(2030, 6, 6),  date(2030, 6, 8)),
        Window("Rosh Hashanah 5791", date(2030, 9, 27), date(2030, 9, 29)),
        Window("Yom Kippur 5791",  date(2030, 10, 6), date(2030, 10, 7)),
        Window("Sukkot 1-2 5791",  date(2030, 10, 11), date(2030, 10, 13)),
        Window("Shmini Atzeret/Simchat Torah 5791", date(2030, 10, 18), date(2030, 10, 20)),
    )

    // Israel: single-day Yom Tov for Pesach 1, 7, Shavuot, Sukkot 1, and
    // Shmini Atzeret. Rosh Hashanah is still 2 days. Yom Kippur is 1 day.
    private val YOM_TOV_ISRAEL: List<Window> = listOf(
        Window("Pesach 1 5786",  date(2026, 4, 1),  date(2026, 4, 2)),
        Window("Pesach 7 5786",  date(2026, 4, 7),  date(2026, 4, 8)),
        Window("Shavuot 5786",   date(2026, 5, 21), date(2026, 5, 22)),
        Window("Rosh Hashanah 5787", date(2026, 9, 11), date(2026, 9, 13)),
        Window("Yom Kippur 5787", date(2026, 9, 20), date(2026, 9, 21)),
        Window("Sukkot 1 5787",  date(2026, 9, 25), date(2026, 9, 26)),
        Window("Shmini Atzeret 5787", date(2026, 10, 2), date(2026, 10, 3)),

        Window("Pesach 1 5787",  date(2027, 4, 21), date(2027, 4, 22)),
        Window("Pesach 7 5787",  date(2027, 4, 27), date(2027, 4, 28)),
        Window("Shavuot 5787",   date(2027, 6, 10), date(2027, 6, 11)),
        Window("Rosh Hashanah 5788", date(2027, 10, 1), date(2027, 10, 3)),
        Window("Yom Kippur 5788", date(2027, 10, 10), date(2027, 10, 11)),
        Window("Sukkot 1 5788",  date(2027, 10, 15), date(2027, 10, 16)),
        Window("Shmini Atzeret 5788", date(2027, 10, 22), date(2027, 10, 23)),

        Window("Pesach 1 5788",  date(2028, 4, 10), date(2028, 4, 11)),
        Window("Pesach 7 5788",  date(2028, 4, 16), date(2028, 4, 17)),
        Window("Shavuot 5788",   date(2028, 5, 30), date(2028, 5, 31)),
        Window("Rosh Hashanah 5789", date(2028, 9, 20), date(2028, 9, 22)),
        Window("Yom Kippur 5789", date(2028, 9, 29), date(2028, 9, 30)),
        Window("Sukkot 1 5789",  date(2028, 10, 4), date(2028, 10, 5)),
        Window("Shmini Atzeret 5789", date(2028, 10, 11), date(2028, 10, 12)),

        Window("Pesach 1 5789",  date(2029, 3, 30), date(2029, 3, 31)),
        Window("Pesach 7 5789",  date(2029, 4, 5),  date(2029, 4, 6)),
        Window("Shavuot 5789",   date(2029, 5, 19), date(2029, 5, 20)),
        Window("Rosh Hashanah 5790", date(2029, 9, 9), date(2029, 9, 11)),
        Window("Yom Kippur 5790", date(2029, 9, 18), date(2029, 9, 19)),
        Window("Sukkot 1 5790",  date(2029, 9, 23), date(2029, 9, 24)),
        Window("Shmini Atzeret 5790", date(2029, 9, 30), date(2029, 10, 1)),

        Window("Pesach 1 5790",  date(2030, 4, 17), date(2030, 4, 18)),
        Window("Pesach 7 5790",  date(2030, 4, 23), date(2030, 4, 24)),
        Window("Shavuot 5790",   date(2030, 6, 6),  date(2030, 6, 7)),
        Window("Rosh Hashanah 5791", date(2030, 9, 27), date(2030, 9, 29)),
        Window("Yom Kippur 5791", date(2030, 10, 6), date(2030, 10, 7)),
        Window("Sukkot 1 5791",  date(2030, 10, 11), date(2030, 10, 12)),
        Window("Shmini Atzeret 5791", date(2030, 10, 18), date(2030, 10, 19)),
    )

    private fun date(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d)
}
