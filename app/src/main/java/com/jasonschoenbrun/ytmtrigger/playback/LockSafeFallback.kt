package com.jasonschoenbrun.ytmtrigger.playback

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.data.MediaEntries
import com.jasonschoenbrun.ytmtrigger.data.MediaKind
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Finds something that will actually play when the phone is locked.
 *
 * A secure lock stops anything played by another app: starting YouTube Music or
 * the Aleph Beta app means opening their screen, and Android will not open a
 * screen over a PIN keyguard. Podcasts are different - this app plays them
 * itself, in a service, with no window - so they play locked exactly as they do
 * unlocked. Measured on the device, not assumed.
 *
 * So a locked phone does not have to mean silence. It means playing the part of
 * the block that still works, and saying why.
 *
 * Two places are searched, nearer first. The rest of this block's own queue,
 * because the household chose those shows for this hour and one of them is
 * likely still playable. Then the default entries in Settings, which is the
 * standing answer to "play something" and the right fallback when a block
 * happens to be all music.
 */
object LockSafeFallback {

    /**
     * Whether this app can start [kind] with the phone locked.
     *
     * The distinction is who does the playing. We play a podcast feed ourselves,
     * and a Spotify show link resolves to a feed we then play, so both are
     * service-only and survive a keyguard. Everything else hands off to another
     * app's UI, which a secure lock forbids.
     */
    fun playsWhileLocked(kind: MediaKind): Boolean = when (kind) {
        MediaKind.PodcastFeed, MediaKind.SpotifyShow -> true
        MediaKind.YtmPlaylist, MediaKind.YtmTrack, MediaKind.AlephBeta, MediaKind.Unknown -> false
    }

    /** A replacement, with where it came from, or null when nothing will play. */
    data class Choice(
        val entry: String,
        /** Index within the schedule, or null when it came from Settings. */
        val queueIndex: Int?,
        val fromSettings: Boolean,
        val label: String,
    )

    /**
     * Something in [schedule] or in Settings that will play while locked.
     *
     * [skipIndex] is the entry that could not play; it is never returned. The
     * search runs forwards from there and then wraps, so the substitute is the
     * next playable thing the household would have heard anyway rather than
     * always the first entry.
     */
    fun find(context: Context, schedule: Schedule, skipIndex: Int): Choice? {
        val urls = schedule.playlistUrls
        if (urls.isNotEmpty()) {
            for (step in 1..urls.size) {
                val i = ((skipIndex + step) % urls.size + urls.size) % urls.size
                if (i == skipIndex) continue
                val raw = urls[i]
                val parsed = MediaEntries.parse(raw)
                if (!playsWhileLocked(parsed.kind)) continue
                return Choice(raw, i, false, parsed.displayName)
            }
        }
        // Nothing in this block survives a lock - a music-only block, say - so
        // fall back to the standing defaults.
        val defaults = SettingsRepository.get(context).current().defaultPlaylistUrls
        for (raw in defaults) {
            val parsed = MediaEntries.parse(raw)
            if (!playsWhileLocked(parsed.kind)) continue
            return Choice(raw, null, true, parsed.displayName)
        }
        Logger.w("LockFallback", "Nothing plays while locked", mapOf(
            "scheduleId" to schedule.id,
            "entries" to urls.size.toString(),
            "defaults" to defaults.size.toString(),
        ))
        return null
    }

    /** What to say out loud before playing the substitute. */
    fun announcement(blocked: String, choice: Choice): String {
        val where = if (choice.fromSettings) "from your default list" else "from this block"
        return "$blocked can't play while the phone is locked. " +
            "Playing ${choice.label} instead, $where."
    }
}
