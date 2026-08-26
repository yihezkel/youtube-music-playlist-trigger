package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.Context

/**
 * When a running block is due to stop, for blocks stopped by duration rather
 * than by a clock time.
 *
 * A clock stop can be worked out from the schedule at any moment. A stop
 * measured in minutes from the start cannot: by the time the third episode of a
 * block is choosing whether it fits, the start time is long gone. So the
 * absolute stop is written down when the block begins and read back for the
 * rest of it, which is what lets the "is there enough of the block left for this
 * episode" rule work for a duration-stopped block too.
 */
object AutoStop {
    private const val PREFS = "autostop"

    fun record(context: Context, scheduleId: String, atMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(scheduleId, atMs).apply()
    }

    /** Epoch millis this block stops, or null when it is not running or has passed. */
    fun endsAt(context: Context, scheduleId: String): Long? {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(scheduleId, 0L)
        return if (at > System.currentTimeMillis()) at else null
    }

    fun clear(context: Context, scheduleId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(scheduleId).apply()
    }
}
