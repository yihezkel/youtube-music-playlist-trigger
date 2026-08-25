package com.jasonschoenbrun.ytmtrigger.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

@Serializable
data class Schedule(    val id: String = UUID.randomUUID().toString(),
    val name: String = "Morning",
    val enabled: Boolean = true,
    /** ISO day-of-week numbers: 1=Monday .. 7=Sunday */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Minutes since midnight, 0..1439 */
    val timeMinutes: Int = 7 * 60 + 30,
    /**
     * What [timeMinutes] is measured against.
     *
     * [TimeAnchor.FixedClock] keeps the historical behaviour: [timeMinutes] is
     * a wall-clock time. The other anchors ignore [timeMinutes] and instead
     * derive the trigger from that day's sunset or from the end of Shabat /
     * Yom Tov, shifted by [anchorOffsetMinutes]. Sunset in Israel moves by
     * over three hours across the year, so a fixed clock time drifts relative
     * to it - that drift is exactly what these anchors remove.
     */
    val timeAnchor: TimeAnchor = TimeAnchor.FixedClock,
    /**
     * Minutes to add to the anchor; negative means before it. Ignored when
     * [timeAnchor] is [TimeAnchor.FixedClock].
     */
    val anchorOffsetMinutes: Int = 0,
    /**
     * Clock time at which to pause playback, or null for "play until stopped".
     * Null by default. When it is at or before [timeMinutes] it is treated as
     * belonging to the following day, so an overnight schedule works.
     */
    val stopTimeMinutes: Int? = null,
    val playlistUrls: List<String> = emptyList(),
    val targetVolumePercent: Int? = null,
    val autoStopMinutes: Int? = null,
    val enableShuffle: Boolean = true,
    val skipFirstTrack: Boolean = true,
    /**
     * Which episode to play when an entry is a podcast. Random by default so a
     * daily schedule works through the back catalogue instead of replaying the
     * newest episode until another is published.
     */
    val podcastEpisodeMode: PodcastEpisodeMode = PodcastEpisodeMode.Random,
    val lastPickedPlaylistIds: List<String> = emptyList(),
) {
    fun localTime(): LocalTime = LocalTime.of(timeMinutes / 60, timeMinutes % 60)
    fun stopLocalTime(): LocalTime? =
        stopTimeMinutes?.let { LocalTime.of(it / 60, it % 60) }
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

/** Which episode of a podcast a schedule should play. */
@Serializable
enum class PodcastEpisodeMode { Random, Latest }

/**
 * What a schedule's trigger time is measured from.
 *
 * [Sunset] and [ShabatYomTovEnd] both move with the calendar, which is the
 * point: a fixed clock time drifts by hours against sunset over the year.
 * [ShabatYomTovEnd] is not the same as a sunset offset, because it also
 * covers Yom Tov and multi-day festivals, which a bare sunset offset gets
 * wrong.
 */
@Serializable
enum class TimeAnchor {
    /** Plain wall-clock time. The historical, and still default, behaviour. */
    FixedClock,

    /** That day's sunset at the configured latitude / longitude. */
    Sunset,

    /**
     * Nightfall at the end of a Shabat or Yom Tov window. A day on which no
     * window ends produces no occurrence, so such a schedule fires only on
     * motzaei Shabat / Yom Tov even if more days are ticked.
     */
    ShabatYomTovEnd,
}

/**
 * A playlist entry is a URL, optionally followed by a display name in square
 * brackets:
 *
 *     https://music.youtube.com/playlist?list=PLKNLlLCOCLas&si=txZZ [Quora]
 *
 * The name is purely cosmetic - nothing is ever launched by URL, only by the
 * extracted id - but every accessor here tolerates both forms so a labelled
 * entry can be used anywhere a bare URL was.
 *
 * Kept as the playlist-shaped view of an entry; [MediaEntries] classifies the
 * wider set (songs, podcast feeds, Spotify shows).
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
