package com.jasonschoenbrun.ytmtrigger.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

@Serializable
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Morning",
    val enabled: Boolean = true,
    /** ISO day-of-week numbers: 1=Monday .. 7=Sunday */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Minutes since midnight, 0..1439 */
    val timeMinutes: Int = 7 * 60 + 30,
    val playlistUrls: List<String> = emptyList(),
    val targetVolumePercent: Int? = null,
    val autoStopMinutes: Int? = null,
    val enableShuffle: Boolean = true,
    val skipFirstTrack: Boolean = true,
    val lastPickedPlaylistIds: List<String> = emptyList(),
) {
    fun localTime(): LocalTime = LocalTime.of(timeMinutes / 60, timeMinutes % 60)
    fun dayOfWeekSet(): Set<DayOfWeek> = daysOfWeek.map { DayOfWeek.of(it) }.toSet()

    companion object {
        fun default() = Schedule()
        fun fromDefaults(s: AppSettings) = Schedule(
            playlistUrls = s.defaultPlaylistUrls,
            targetVolumePercent = s.defaultVolumePercent,
            enableShuffle = s.defaultEnableShuffle,
            skipFirstTrack = s.defaultSkipFirstTrack,
        )
    }
}

object PlaylistUrl {
    private val ID_REGEX = Regex("[?&]list=([A-Za-z0-9_-]+)")
    fun extractId(url: String): String? = ID_REGEX.find(url)?.groupValues?.getOrNull(1)
    fun isValid(url: String): Boolean = extractId(url) != null
}
