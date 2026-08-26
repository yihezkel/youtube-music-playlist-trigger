package com.jasonschoenbrun.ytmtrigger.playback

import com.jasonschoenbrun.ytmtrigger.data.MediaEntries
import com.jasonschoenbrun.ytmtrigger.data.MediaEntry
import com.jasonschoenbrun.ytmtrigger.data.MediaKind
import com.jasonschoenbrun.ytmtrigger.data.PodcastEpisodeMode
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlin.random.Random

object PlaylistPicker {
    /**
     * Choose one entry from the schedule, or null when the pool is empty.
     *
     * The pool accepts anything [MediaEntries] recognises - playlists, single
     * tracks and podcasts alike. It used to require a `list=` parameter, which
     * would have silently dropped every song and podcast from the rotation.
     */
    fun pick(repo: ScheduleRepository, schedule: Schedule, rand: Random = Random.Default): Choice? {
        val pool = schedule.playlistUrls
            .map { MediaEntries.parse(it) }
            .filter { it.kind != MediaKind.Unknown }
        if (pool.isEmpty()) {
            Logger.w("Picker", "Empty playlist pool", mapOf("scheduleId" to schedule.id))
            return null
        }
        val skip = schedule.lastPickedPlaylistIds.toSet()
        val candidates = pool.filter { it.id !in skip }.ifEmpty { pool }
        val picked = candidates[rand.nextInt(candidates.size)]
        Logger.i("Picker", "Picked entry", mapOf(
            "scheduleId" to schedule.id,
            "kind" to picked.kind.name,
            "id" to picked.id,
            "name" to (picked.label ?: "-"),
            "candidateCount" to candidates.size.toString(),
            "totalPool" to pool.size.toString(),
        ))
        return Choice(
            url = MediaEntries.url(picked.raw),
            playlistId = picked.id,
            label = picked.label,
            kind = picked.kind,
            episodeMode = picked.episodeMode,
        )
    }

    /**
     * The entry at [index] of a continuous schedule.
     *
     * With [wrap] the queue restarts from the top past its end, which is what
     * keeps a timed block full: a queue of four shows totalling two hours has
     * to start again to cover a three-hour block. Without it the queue is
     * finite and running off the end returns null, which is how a block with no
     * stop time ends with its last episode instead of playing on all night.
     *
     * Going round again only yields new content for entries that re-pick -
     * random draws a different episode, sequential advances. A "newest episode"
     * entry does not: `episodes.first()` is deterministic, so a second lap
     * would replay the identical episode. Those entries are therefore passed
     * over on any lap after the first, handing the slot to the next random or
     * sequential show. The skip is bounded, so a queue made entirely of newest
     * entries still plays rather than falling silent.
     *
     * The entry actually chosen is reported back as [Choice.index] so the
     * player chains on from there; advancing from the requested index would
     * land straight back on the entry just skipped.
     */
    fun at(schedule: Schedule, index: Int, wrap: Boolean = true): Choice? {
        val pool = schedule.playlistUrls
            .map { MediaEntries.parse(it) }
            .filter { it.kind != MediaKind.Unknown }
        if (pool.isEmpty()) {
            Logger.w("Picker", "Empty playlist pool", mapOf("scheduleId" to schedule.id))
            return null
        }
        if (!wrap && index >= pool.size) {
            Logger.i("Picker", "Queue exhausted", mapOf(
                "scheduleId" to schedule.id, "of" to pool.size.toString(),
            ))
            return null
        }
        fun slot(i: Int) = ((i % pool.size) + pool.size) % pool.size
        var resolved = index
        var skipped = 0
        if (index >= pool.size) {
            while (skipped < pool.size && repeatsOnWrap(pool[slot(resolved)], schedule)) {
                resolved++
                skipped++
            }
        }
        val picked = pool[slot(resolved)]
        Logger.i("Picker", "Queue entry", mapOf(
            "scheduleId" to schedule.id,
            "index" to resolved.toString(),
            "of" to pool.size.toString(),
            "lap" to (resolved / pool.size).toString(),
            "skippedNewest" to skipped.toString(),
            "kind" to picked.kind.name,
            "name" to (picked.label ?: picked.id),
        ))
        return Choice(
            url = MediaEntries.url(picked.raw),
            playlistId = picked.id,
            label = picked.label,
            kind = picked.kind,
            episodeMode = picked.episodeMode,
            index = resolved,
        )
    }

    /** True when replaying [entry] would hand back the episode it already played. */
    private fun repeatsOnWrap(entry: MediaEntry, schedule: Schedule): Boolean =
        (entry.kind == MediaKind.PodcastFeed || entry.kind == MediaKind.SpotifyShow) &&
            (entry.episodeMode ?: schedule.podcastEpisodeMode) == PodcastEpisodeMode.Latest

    data class Choice(
        val url: String,
        /** Playlist id, video id or feed URL, depending on [kind]. */
        val playlistId: String,
        val label: String? = null,
        val kind: MediaKind = MediaKind.YtmPlaylist,
        /**
         * Carried explicitly because [url] is the bare URL with the brackets
         * stripped: re-parsing it downstream cannot recover anything the
         * brackets held.
         */
        val episodeMode: PodcastEpisodeMode? = null,
        /**
         * Which queue entry this is, after any wrap-lap skipping. Chaining uses
         * this rather than the requested index. Always 0 for a single pick.
         */
        val index: Int = 0,
    )
}
