package com.jasonschoenbrun.ytmtrigger.podcast

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Tracks how far through a serialised show the household has got.
 *
 * Some shows tell one story across numbered parts - Business Wars ran "part 1"
 * one morning and "part 5" of an unrelated series the same afternoon under
 * random selection, which is close to useless. Sequential mode walks the feed
 * oldest-first and remembers the last episode heard.
 *
 * The mark advances only when an episode finishes, so an episode cut off by a
 * block's stop time is resumed next time (via [PodcastResume]) rather than
 * skipped. Kept separate from that class because the two answer different
 * questions: "where inside this episode" versus "which episode next".
 */
object PodcastSequence {

    private const val PREFS = "podcast-sequence"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The episode to play next: the one after the last finished, oldest first.
     *
     * [episodes] arrives newest-first, as feeds publish it. Falls back to the
     * oldest episode when nothing is marked, and when the marked episode has
     * dropped out of the feed - publishers truncate, and a vanished mark must
     * not strand the show.
     */
    fun next(context: Context, feedUrl: String, episodes: List<Episode>): Episode? {
        if (episodes.isEmpty()) return null
        val oldestFirst = episodes.sortedBy { it.publishedMs }
        val lastUrl = prefs(context).getString(key(feedUrl), null)
            ?: return oldestFirst.first()
        val idx = oldestFirst.indexOfFirst { it.audioUrl == lastUrl }
        if (idx < 0) {
            Logger.w("PodcastSequence", "Marked episode no longer in feed; starting from oldest", mapOf(
                "feed" to feedUrl,
            ))
            return oldestFirst.first()
        }
        // Past the end means the show has been heard through; start again
        // rather than going silent.
        return oldestFirst.getOrNull(idx + 1) ?: oldestFirst.first().also {
            Logger.i("PodcastSequence", "Reached the end of the feed; starting over", mapOf(
                "feed" to feedUrl,
            ))
        }
    }

    /** Record that [audioUrl] was heard to the end. */
    fun markPlayed(context: Context, feedUrl: String, audioUrl: String) {
        prefs(context).edit().putString(key(feedUrl), audioUrl).apply()
        Logger.i("PodcastSequence", "Marked played", mapOf("feed" to feedUrl))
    }

    private fun key(feedUrl: String) = "${feedUrl.hashCode()}:last"
}
