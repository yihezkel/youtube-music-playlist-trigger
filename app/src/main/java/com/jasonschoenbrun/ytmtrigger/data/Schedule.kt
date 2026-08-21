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

/**
 * A playlist entry is a URL, optionally followed by a display name in square
 * brackets:
 *
 *     https://music.youtube.com/playlist?list=PLKNLlLCOCLas&si=txZZ [Quora]
 *
 * The name is purely cosmetic - nothing is ever launched by URL, only by the
 * extracted playlist id - but every accessor here tolerates both forms so a
 * labelled entry can be used anywhere a bare URL was.
 */
object PlaylistUrl {
    private val ID_REGEX = Regex("[?&]list=([A-Za-z0-9_-]+)")

    /** The bare URL, with any trailing " [Name]" removed. */
    fun url(entry: String): String = entry.trim().substringBefore(' ').trim()

    /** The bracketed name, or null when the entry is a plain URL. */
    fun label(entry: String): String? {
        val rest = entry.trim().substringAfter(' ', "").trim()
        if (!rest.startsWith("[") || !rest.endsWith("]") || rest.length <= 2) return null
        return rest.substring(1, rest.length - 1).trim().ifBlank { null }
    }

    /** What to show a human: the name when there is one, else the URL. */
    fun display(entry: String): String = label(entry) ?: url(entry)

    /** Recombine into the stored form. */
    fun format(url: String, label: String?): String =
        if (label.isNullOrBlank()) url.trim() else "${url.trim()} [${label.trim()}]"

    fun extractId(entry: String): String? =
        ID_REGEX.find(url(entry))?.groupValues?.getOrNull(1)

    fun isValid(entry: String): Boolean = extractId(entry) != null
}
