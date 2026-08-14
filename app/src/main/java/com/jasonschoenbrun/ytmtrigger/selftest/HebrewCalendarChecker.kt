package com.jasonschoenbrun.ytmtrigger.selftest

import com.jasonschoenbrun.ytmtrigger.log.Logger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Suppresses the self-test on Shabat and Yom Tov to avoid waking the household
 * (or anyone nearby) with the failure alert TTS.
 *
 * Day windows are conservative approximations of sunset/nightfall to avoid
 * needing the device's geo location. Shabat = Friday 17:30 -> Saturday 21:30
 * local. Yom Tov windows are hardcoded from Hebcal Diaspora dates and span
 * "sunset of day before" (17:30) to "nightfall of last day" (21:30). The
 * windows are intentionally wide; a missed self-test is far better than an
 * alarm going off on chag.
 *
 * Date table covers 2026-2030. After that the self-test runs normally on
 * holidays unless the table is updated (a fall-open default per user request:
 * "if anything is unexpected, log it" - logging will reveal this).
 */
object HebrewCalendarChecker {

    private val DAY_START: LocalTime = LocalTime.of(17, 30)
    private val DAY_END: LocalTime = LocalTime.of(21, 30)

    data class Window(val name: String, val startDay: LocalDate, val endDay: LocalDate) {
        fun contains(now: LocalDateTime): Boolean {
            val startInst = LocalDateTime.of(startDay, DAY_START)
            val endInst = LocalDateTime.of(endDay, DAY_END)
            return !now.isBefore(startInst) && !now.isAfter(endInst)
        }
    }

    /** Result for callers; includes the matching window name when applicable. */
    data class Result(val skip: Boolean, val reason: String?)

    fun check(now: LocalDateTime, israeliObservance: Boolean): Result {
        // Shabat: Friday DAY_START (sunset) -> Saturday DAY_END (nightfall).
        if (now.dayOfWeek == DayOfWeek.FRIDAY && !now.toLocalTime().isBefore(DAY_START)) {
            return Result(true, "Shabat (Friday evening)")
        }
        if (now.dayOfWeek == DayOfWeek.SATURDAY && !now.toLocalTime().isAfter(DAY_END)) {
            return Result(true, "Shabat (Saturday)")
        }
        // Yom Tov.
        val table = if (israeliObservance) YOM_TOV_ISRAEL else YOM_TOV_DIASPORA
        val hit = table.firstOrNull { it.contains(now) }
        if (hit != null) return Result(true, "Yom Tov: ${hit.name}")
        // Past the end of the hardcoded table: log a warning so it's visible.
        val maxYear = (if (israeliObservance) YOM_TOV_ISRAEL else YOM_TOV_DIASPORA)
            .maxOf { it.endDay.year }
        if (now.year > maxYear) {
            Logger.w("HebrewCal", "Yom Tov table exhausted; please update YOM_TOV_*", mapOf(
                "currentYear" to now.year.toString(),
                "tableMaxYear" to maxYear.toString(),
                "israeliObservance" to israeliObservance.toString(),
            ))
        }
        return Result(false, null)
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
