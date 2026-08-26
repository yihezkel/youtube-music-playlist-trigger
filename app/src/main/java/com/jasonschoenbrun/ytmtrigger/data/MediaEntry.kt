package com.jasonschoenbrun.ytmtrigger.data

/**
 * A thing a schedule can play.
 *
 * Entries are stored as text, optionally with a display name in brackets, and
 * optionally with a per-entry episode mode after a pipe:
 *
 *     https://music.youtube.com/playlist?list=PL...        [Best]
 *     https://music.youtube.com/watch?v=755MWeNQ34g        [A song]
 *     https://rss.libsyn.com/shows/200396/....xml          [Breitowitz Q&A]
 *     https://feeds.npr.org/510325/podcast.xml             [The Indicator | newest]
 *     https://rss.art19.com/business-wars                  [Business Wars | sequential]
 *
 * The mode matters because one block mixes shows with opposite needs: a daily
 * news round-up is only useful newest-first, an evergreen archive is best
 * shuffled, and a serialised show that tells one story across numbered parts
 * is ruined by either. Without a per-entry mode the whole block had to share
 * one setting.
 *
 * The kind is inferred from the URL, so nothing extra has to be recorded and
 * an entry can be pasted straight from a Share button.
 */
enum class MediaKind {
    /** A YouTube Music playlist, album or radio mix. Needs the Play button. */
    YtmPlaylist,

    /** A single YouTube Music track. Deep-links straight into playback. */
    YtmTrack,

    /** A podcast RSS feed we play ourselves. */
    PodcastFeed,

    /** A Spotify show, resolved to its public RSS feed before playing. */
    SpotifyShow,

    /** Not recognised. */
    Unknown,
}

data class MediaEntry(
    val raw: String,
    val kind: MediaKind,
    /** Playlist id, video id, feed URL or Spotify show id, per [kind]. */
    val id: String,
    val label: String?,
    /** Per-entry override; null means "use the schedule's setting". */
    val episodeMode: PodcastEpisodeMode? = null,
) {
    val displayName: String get() = label ?: id
}

object MediaEntries {

    private val YT_LIST = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val YT_VIDEO = Regex("[?&]v=([A-Za-z0-9_-]{6,})")
    private val SPOTIFY_SHOW = Regex("open\\.spotify\\.com/show/([A-Za-z0-9]+)|spotify:show:([A-Za-z0-9]+)")

    /** The bare URL, with any trailing " [Name]" removed. */
    fun url(entry: String): String = entry.trim().substringBefore(' ').trim()

    /** Everything inside the brackets, or null when there are none. */
    private fun bracketed(entry: String): String? {
        val rest = entry.trim().substringAfter(' ', "").trim()
        if (!rest.startsWith("[") || !rest.endsWith("]") || rest.length <= 2) return null
        return rest.substring(1, rest.length - 1).trim().ifBlank { null }
    }

    /** The bracketed name, with any `| mode` suffix removed. */
    fun label(entry: String): String? =
        bracketed(entry)?.substringBefore('|')?.trim()?.ifBlank { null }

    /**
     * The per-entry episode mode, or null when the entry does not name one.
     *
     * An unrecognised word is ignored rather than rejected: the text is typed
     * by hand, and a typo should fall back to the schedule's setting instead
     * of silently dropping the entry from the rotation.
     */
    fun episodeMode(entry: String): PodcastEpisodeMode? {
        val raw = bracketed(entry)?.substringAfter('|', "")?.trim()?.lowercase()
        if (raw.isNullOrBlank()) return null
        return when (raw) {
            "newest", "latest" -> PodcastEpisodeMode.Latest
            "random", "shuffle" -> PodcastEpisodeMode.Random
            "sequential", "inorder", "in-order", "order" -> PodcastEpisodeMode.Sequential
            else -> null
        }
    }

    fun format(url: String, label: String?, mode: PodcastEpisodeMode? = null): String {
        val inside = listOfNotNull(
            label?.trim()?.ifBlank { null },
            mode?.name?.lowercase(),
        ).joinToString(" | ")
        return if (inside.isBlank()) url.trim() else "${url.trim()} [$inside]"
    }

    /**
     * Classify an entry.
     *
     * A `watch?v=` link that also carries `list=` is treated as a playlist:
     * that is what a "share" from a radio mix produces, and honouring the list
     * keeps playing after the first track instead of stopping dead.
     */
    fun parse(entry: String): MediaEntry {
        val url = url(entry)
        val label = label(entry)
        val mode = episodeMode(entry)
        val lower = url.lowercase()

        SPOTIFY_SHOW.find(url)?.let { m ->
            val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return MediaEntry(entry, MediaKind.SpotifyShow, id, label, mode)
        }
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            YT_LIST.find(url)?.let {
                return MediaEntry(entry, MediaKind.YtmPlaylist, it.groupValues[1], label, mode)
            }
            YT_VIDEO.find(url)?.let {
                return MediaEntry(entry, MediaKind.YtmTrack, it.groupValues[1], label, mode)
            }
            return MediaEntry(entry, MediaKind.Unknown, url, label, mode)
        }
        // Anything else that looks like a feed. Deliberately permissive:
        // podcast feeds live on every imaginable host and path.
        if (lower.startsWith("http")) {
            return MediaEntry(entry, MediaKind.PodcastFeed, url, label, mode)
        }
        return MediaEntry(entry, MediaKind.Unknown, url, label, mode)
    }

    fun isValid(entry: String): Boolean = parse(entry).kind != MediaKind.Unknown
}
