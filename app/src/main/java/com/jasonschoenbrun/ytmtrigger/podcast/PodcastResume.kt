package com.jasonschoenbrun.ytmtrigger.podcast

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Remembers where an episode was cut off so the next block can pick it up.
 *
 * A block ends on a clock time, not on an episode boundary, so something is
 * always interrupted. Without this the part-heard episode is simply lost and
 * the next block draws a fresh one at random - fine for a 13-minute news
 * round-up, wasteful for a 71-minute shiur abandoned two thirds of the way in.
 *
 * Keyed by feed rather than by schedule: the same show can appear in more than
 * one block, and a listener thinks in terms of "where was I in that show", not
 * "where was I in Tuesday's queue".
 */
object PodcastResume {

    private const val PREFS = "podcast-resume"
    /**
     * How far to rewind when resuming. Dropping back a little re-establishes
     * context; restarting exactly where the audio stopped tends to land
     * mid-sentence.
     */
    const val REWIND_SEC = 5 * 60L
    /** Below this, resuming is not worth it - just start the episode again. */
    private const val MIN_SAVE_SEC = 60L
    /** Within this of the end, treat the episode as finished rather than resumable. */
    private const val NEAR_END_SEC = 120L

    data class Mark(val audioUrl: String, val title: String, val positionSec: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Record where [audioUrl] stopped. Ignored when barely started or nearly
     * finished, so a resume only ever exists for an episode genuinely left
     * part-heard.
     */
    fun save(
        context: Context,
        feedUrl: String,
        audioUrl: String,
        title: String,
        positionSec: Long,
        durationSec: Long,
    ) {
        if (positionSec < MIN_SAVE_SEC) return
        if (durationSec > 0 && positionSec >= durationSec - NEAR_END_SEC) {
            clear(context, feedUrl)
            return
        }
        prefs(context).edit()
            .putString(key(feedUrl, "url"), audioUrl)
            .putString(key(feedUrl, "title"), title)
            .putLong(key(feedUrl, "pos"), positionSec)
            .apply()
        Logger.i("PodcastResume", "Saved position", mapOf(
            "feed" to feedUrl,
            "title" to title,
            "positionSec" to positionSec.toString(),
        ))
    }

    /** The pending resume for [feedUrl], or null. */
    fun get(context: Context, feedUrl: String): Mark? {
        val p = prefs(context)
        val url = p.getString(key(feedUrl, "url"), null) ?: return null
        val pos = p.getLong(key(feedUrl, "pos"), 0L)
        if (pos <= 0) return null
        return Mark(url, p.getString(key(feedUrl, "title"), "").orEmpty(), pos)
    }

    /** Where playback should actually start: [REWIND_SEC] before the mark. */
    fun resumeAtSec(mark: Mark): Long = (mark.positionSec - REWIND_SEC).coerceAtLeast(0L)

    fun clear(context: Context, feedUrl: String) {
        prefs(context).edit()
            .remove(key(feedUrl, "url"))
            .remove(key(feedUrl, "title"))
            .remove(key(feedUrl, "pos"))
            .apply()
    }

    private fun key(feedUrl: String, field: String) = "${feedUrl.hashCode()}:$field"
}
