package com.jasonschoenbrun.ytmtrigger.playback

import com.jasonschoenbrun.ytmtrigger.data.MediaEntries
import com.jasonschoenbrun.ytmtrigger.data.MediaKind
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
        )
    }

    /**
     * The entry at [index] of a continuous schedule, wrapping past the end.
     *
     * Wrapping is what keeps a block full: a queue of four shows totalling two
     * hours has to start again to cover a three-hour block, and because podcast
     * entries re-pick an episode each time, going round again yields fresh
     * content rather than a repeat.
     */
    fun at(schedule: Schedule, index: Int): Choice? {
        val pool = schedule.playlistUrls
            .map { MediaEntries.parse(it) }
            .filter { it.kind != MediaKind.Unknown }
        if (pool.isEmpty()) {
            Logger.w("Picker", "Empty playlist pool", mapOf("scheduleId" to schedule.id))
            return null
        }
        val picked = pool[((index % pool.size) + pool.size) % pool.size]
        Logger.i("Picker", "Queue entry", mapOf(
            "scheduleId" to schedule.id,
            "index" to index.toString(),
            "of" to pool.size.toString(),
            "kind" to picked.kind.name,
            "name" to (picked.label ?: picked.id),
        ))
        return Choice(
            url = MediaEntries.url(picked.raw),
            playlistId = picked.id,
            label = picked.label,
            kind = picked.kind,
        )
    }

    data class Choice(
        val url: String,
        /** Playlist id, video id or feed URL, depending on [kind]. */
        val playlistId: String,
        val label: String? = null,
        val kind: MediaKind = MediaKind.YtmPlaylist,
    )
}
