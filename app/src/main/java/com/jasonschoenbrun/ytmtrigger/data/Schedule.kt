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
    /**
     * Start when the queue of the named schedule finishes, instead of at a
     * clock time.
     *
     * Motzaei Shabat is why this exists. The kids' block is anchored to
     * nightfall, so its start moves by nearly three hours across the year,
     * while the teen block that follows it had a fixed 20:30 start. In midsummer
     * the two collided; in midwinter there was a long gap. A fixed offset from
     * nightfall would not fix it either, because how long the kids' queue runs
     * depends on the episode lengths it happens to draw.
     *
     * A schedule with this set is never armed from the clock - it is started
     * only when the schedule it names has played its last episode.
     */
    val startsAfter: String? = null,
    val playlistUrls: List<String> = emptyList(),
    val targetVolumePercent: Int? = null,
    /**
     * Stop this many minutes after the block starts, when [stopTimeMinutes] is
     * not set. Ignored if it is - a clock stop wins.
     *
     * For a block whose start moves through the year a clock stop gives a
     * different length every week: the kids' motzaei Shabat block, anchored to
     * nightfall against a fixed 20:30 stop, ran two and a half hours in December
     * and nine minutes in August. A duration gives it the same length all year.
     *
     * It also gives a block that ends with music a way to finish. Music is
     * played by YouTube Music rather than by us, so we are never told when it
     * ends and the queue cannot move past it - but it can still be stopped.
     */
    val autoStopMinutes: Int? = null,
    val enableShuffle: Boolean = true,
    val skipFirstTrack: Boolean = true,
    /**
     * Which episode to play when an entry is a podcast. Random by default so a
     * daily schedule works through the back catalogue instead of replaying the
     * newest episode until another is published.
     */
    val podcastEpisodeMode: PodcastEpisodeMode = PodcastEpisodeMode.Random,
    /**
     * Play the entries as a continuous queue rather than picking one.
     *
     * With this off (the default, and the historical behaviour) a trigger picks
     * a single entry and stops when it ends. With it on, the entries play in
     * order and the next one starts the moment the current finishes, wrapping
     * back to the start so the block stays filled until [stopTimeMinutes].
     * That is the only way to fill a window continuously when episode lengths
     * within one show vary threefold.
     *
     * When [stopTimeMinutes] is null the queue does not wrap: the block is the
     * last of its day, so it plays each entry once and ends with the final
     * episode rather than looping on into the night.
     *
     * A podcast entry chains exactly, because this app plays it and is told
     * when it ends. A YouTube Music entry hands control to YT Music, which
     * reports nothing, so its end is found by polling - see [MusicEndWatcher].
     * That is approximate, to within its check interval, but it does mean music
     * no longer has to be the last thing in a queue.
     */
    val continuousPlay: Boolean = false,
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
enum class PodcastEpisodeMode {
    /** Anywhere in the back catalogue. Right for evergreen archives. */
    Random,

    /** The newest episode. Right for news, and for feeds that mix formats. */
    Latest,

    /**
     * The next unheard episode, oldest first.
     *
     * For shows that tell one story across numbered parts, where Random
     * produces part 1 followed by part 5 of a different series. The position
     * is remembered per feed and advances only when an episode is heard to
     * the end, so a block that cuts one off resumes it rather than skipping.
     */
    Sequential,
}

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

    /**
     * The bracketed name, or null when the entry is a plain URL.
     *
     * Delegates to [MediaEntries] so there is one implementation of the entry
     * syntax. Keeping a second copy here meant a `| sequential` suffix ended
     * up displayed as part of the show's name.
     */
    fun label(entry: String): String? = MediaEntries.label(entry)

    /** What to show a human: the name when there is one, else the URL. */
    fun display(entry: String): String = label(entry) ?: url(entry)

    /** Recombine into the stored form, preserving any per-entry mode. */
    fun format(url: String, label: String?): String =
        MediaEntries.format(url, label)

    fun extractId(entry: String): String? =
        ID_REGEX.find(url(entry))?.groupValues?.getOrNull(1)

    fun isValid(entry: String): Boolean = extractId(entry) != null
}
