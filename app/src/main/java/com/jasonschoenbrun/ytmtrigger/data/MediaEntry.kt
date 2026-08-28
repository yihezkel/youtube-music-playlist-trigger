package com.jasonschoenbrun.ytmtrigger.data

/**
 * A thing a schedule can play.
 *
 * Entries are stored as text, optionally with a display name in brackets, and
 * optionally with per-entry qualifiers after a pipe:
 *
 *     https://music.youtube.com/playlist?list=PL...        [Best]
 *     https://music.youtube.com/watch?v=755MWeNQ34g        [A song]
 *     https://rss.libsyn.com/shows/200396/....xml          [Breitowitz Q&A]
 *     https://feeds.npr.org/510325/podcast.xml             [The Indicator | newest]
 *     https://rss.art19.com/business-wars                  [Business Wars | sequential]
 *     https://rss.libsyn.com/shows/104921/....xml          [Jews You Should Know | min 20]
 *
 * The mode matters because one block mixes shows with opposite needs: a daily
 * news round-up is only useful newest-first, an evergreen archive is best
 * shuffled, and a serialised show that tells one story across numbered parts
 * is ruined by either. Without a per-entry mode the whole block had to share
 * one setting.
 *
 * `min N` exists because some feeds carry two formats under one name. Jews You
 * Should Know publishes 45-to-100-minute biography interviews and, under the
 * same feed, a 73-part series of 3-to-7-minute divrei Torah - a quarter of its
 * episodes, with nothing at all between 7 and 25 minutes. A random draw landed
 * on a four-minute parsha thought, usually for the wrong week, one time in
 * four. Smash Boom Best and The School of Greatness have the same shape.
 * Filtering by length picks the format rather than the show.
 *
 * Qualifiers can be combined and appear in any order: `[Name | newest | min 20]`.
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

    /**
     * Something in the Aleph Beta app.
     *
     * Their public RSS feeds carry only the series in progress - four episodes
     * of "A Book Like No Other" where a subscription holds sixty-eight - so a
     * feed cannot reach the archive. The app can, and it publishes a media
     * session, so it can be started, watched and stopped like any other player.
     */
    AlephBeta,

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
    /**
     * Shortest episode worth drawing, in minutes; null means no floor.
     *
     * Separates two formats sharing one feed - see the class comment.
     */
    val minMinutes: Int? = null,
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

    /** The bracketed name, with any `| qualifier` suffixes removed. */
    fun label(entry: String): String? =
        bracketed(entry)?.substringBefore('|')?.trim()?.ifBlank { null }

    /** Every `| qualifier` after the label, lowercased and trimmed. */
    private fun qualifiers(entry: String): List<String> =
        bracketed(entry)
            ?.split('|')
            ?.drop(1)
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * The per-entry episode mode, or null when the entry does not name one.
     *
     * An unrecognised word is ignored rather than rejected: the text is typed
     * by hand, and a typo should fall back to the schedule's setting instead
     * of silently dropping the entry from the rotation. That tolerance is also
     * what lets qualifiers be combined and reordered without a parser change.
     */
    fun episodeMode(entry: String): PodcastEpisodeMode? =
        qualifiers(entry).firstNotNullOfOrNull { q ->
            when (q) {
                "newest", "latest" -> PodcastEpisodeMode.Latest
                "random", "shuffle" -> PodcastEpisodeMode.Random
                "sequential", "inorder", "in-order", "order" -> PodcastEpisodeMode.Sequential
                else -> null
            }
        }

    /**
     * The per-entry minimum episode length in minutes, or null for no floor.
     *
     * Written `min 20`, `min20` or `min=20`. A value that does not parse is
     * ignored for the same reason an unknown mode is: a typo must not quietly
     * empty a show out of the rotation.
     */
    fun minMinutes(entry: String): Int? =
        qualifiers(entry).firstNotNullOfOrNull { q ->
            if (!q.startsWith("min")) return@firstNotNullOfOrNull null
            q.removePrefix("min").trim().removePrefix("=").trim()
                .toIntOrNull()?.takeIf { it > 0 }
        }

    /**
     * Qualifiers that are neither a known mode nor a length floor.
     *
     * A structured editor rebuilds the entry text from the fields it knows
     * about, so without this anything it does not model - a future qualifier,
     * or a typo worth keeping so it can be corrected - would be silently
     * dropped the first time the entry was opened and saved.
     */
    fun otherQualifiers(entry: String): List<String> =
        qualifiers(entry).filter { q ->
            q !in setOf(
                "newest", "latest", "random", "shuffle",
                "sequential", "inorder", "in-order", "order",
            ) && !(q.startsWith("min") && q.removePrefix("min").trim().removePrefix("=").trim().toIntOrNull() != null)
        }

    fun format(
        url: String,
        label: String?,
        mode: PodcastEpisodeMode? = null,
        minMinutes: Int? = null,
        extra: List<String> = emptyList(),
    ): String {
        val inside = (
            listOfNotNull(
                label?.trim()?.ifBlank { null },
                mode?.name?.lowercase(),
                minMinutes?.takeIf { it > 0 }?.let { "min $it" },
            ) + extra.map { it.trim() }.filter { it.isNotBlank() }
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
        val min = minMinutes(entry)
        val lower = url.lowercase()

        SPOTIFY_SHOW.find(url)?.let { m ->
            val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return MediaEntry(entry, MediaKind.SpotifyShow, id, label, mode, min)
        }
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            YT_LIST.find(url)?.let {
                return MediaEntry(entry, MediaKind.YtmPlaylist, it.groupValues[1], label, mode, min)
            }
            YT_VIDEO.find(url)?.let {
                return MediaEntry(entry, MediaKind.YtmTrack, it.groupValues[1], label, mode, min)
            }
            return MediaEntry(entry, MediaKind.Unknown, url, label, mode, min)
        }
        // Aleph Beta before the generic feed branch below: these are ordinary
        // https links and would otherwise be mistaken for an RSS feed and
        // parsed as XML. The app has verified App Links for both hosts, so the
        // URL opens the app rather than a browser.
        if (lower.contains("alephbeta.org")) {
            return MediaEntry(entry, MediaKind.AlephBeta, url, label, mode, min)
        }
        // Anything else that looks like a feed. Deliberately permissive:
        // podcast feeds live on every imaginable host and path.
        if (lower.startsWith("http")) {
            return MediaEntry(entry, MediaKind.PodcastFeed, url, label, mode, min)
        }
        return MediaEntry(entry, MediaKind.Unknown, url, label, mode, min)
    }

    fun isValid(entry: String): Boolean = parse(entry).kind != MediaKind.Unknown
}
