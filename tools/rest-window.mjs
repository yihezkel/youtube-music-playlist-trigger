// Is a given instant inside Shabat or Yom Tov, in Israel?
//
// The health check needs this because the phone is deliberately switched off
// for Shabat. Without it the watchdog fires every single week: on 19 September
// 2026 it opened an issue and sent mail at 12:49 on Shabbat Shuva, reporting a
// phone that was off exactly as intended. An alarm that cries wolf weekly is
// one you stop reading, which defeats the point of having it.
//
// Israeli observance (il: true) to match the app's own default, and Jerusalem
// for the candle-lighting times. Being a few minutes out either way does not
// matter here: the question is "is the phone expected to be off", and it is
// off for hours, not minutes.
import { HebrewCalendar, Location, flags } from "@hebcal/core";

const LOCATION = Location.lookup("Jerusalem");

/**
 * True when [nowMs] falls between a candle lighting and the havdalah that ends
 * that window.
 *
 * Looks a few days either side so a window that opened yesterday is still
 * found, and so a Yom Tov running into Shabat is treated as one long window
 * rather than two - the phone stays off across the join.
 */
export function isRestWindow(nowMs = Date.now()) {
    const now = new Date(nowMs);
    const start = new Date(nowMs - 4 * 86400_000);
    const end = new Date(nowMs + 4 * 86400_000);

    const events = HebrewCalendar.calendar({
        start, end, location: LOCATION, candlelighting: true, il: true,
    })
        .filter((e) => e.eventTime)
        .map((e) => ({
            at: e.eventTime.getTime(),
            opens: Boolean(e.getFlags() & flags.LIGHT_CANDLES),
        }))
        .sort((a, b) => a.at - b.at);

    // Walk forward; the state after the last event at or before now is the
    // answer. Consecutive openings (Yom Tov into Shabat) simply keep it open.
    let inside = false;
    for (const e of events) {
        if (e.at > nowMs) break;
        inside = e.opens;
    }
    return inside;
}

/** For messages: what the current window is, or null when there is none. */
export function restWindowName(nowMs = Date.now()) {
    if (!isRestWindow(nowMs)) return null;
    const now = new Date(nowMs);
    const evs = HebrewCalendar.calendar({
        start: new Date(nowMs - 2 * 86400_000), end: new Date(nowMs + 86400_000),
        location: LOCATION, candlelighting: true, il: true,
    }).filter((e) => !e.eventTime && e.getDesc() !== "");
    // The named holiday or parsha covering today, if the library offers one.
    return evs.length ? evs[evs.length - 1].getDesc() : "Shabat / Yom Tov";
}
