package com.jasonschoenbrun.ytmtrigger.playback

import com.jasonschoenbrun.ytmtrigger.data.PlaylistUrl
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlin.random.Random

object PlaylistPicker {
    /** Returns chosen playlist URL & id, or null if pool is empty. */
    fun pick(repo: ScheduleRepository, schedule: Schedule, rand: Random = Random.Default): Choice? {
        val pool = schedule.playlistUrls.mapNotNull { url ->
            val id = PlaylistUrl.extractId(url) ?: return@mapNotNull null
            url to id
        }
        if (pool.isEmpty()) {
            Logger.w("Picker", "Empty playlist pool", mapOf("scheduleId" to schedule.id))
            return null
        }
        val skip = schedule.lastPickedPlaylistIds.toSet()
        val candidates = pool.filter { it.second !in skip }.ifEmpty { pool }
        val picked = candidates[rand.nextInt(candidates.size)]
        Logger.i("Picker", "Picked playlist", mapOf(
            "scheduleId" to schedule.id,
            "playlistId" to picked.second,
            "candidateCount" to candidates.size.toString(),
            "totalPool" to pool.size.toString(),
        ))
        return Choice(url = picked.first, playlistId = picked.second)
    }

    data class Choice(val url: String, val playlistId: String)
}
