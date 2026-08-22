package com.jasonschoenbrun.ytmtrigger.podcast

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds the public RSS feed behind a Spotify show.
 *
 * Spotify's own Web API would answer this directly but needs a Premium
 * account, and its public show page renders only twelve episodes. Almost every
 * podcast on Spotify is syndicated there *from* an RSS feed, so the feed is
 * both freely reachable and strictly more complete - 384 episodes versus 12
 * for the show this was built against.
 *
 * The mapping is: Spotify show page -> show title -> iTunes podcast search ->
 * feed URL. Both hops are public and unauthenticated. The result is cached
 * permanently, since a show's feed does not change.
 *
 * Shows that exist only on Spotify (its own originals and exclusives) have no
 * feed by design and cannot be resolved; that is reported rather than guessed
 * at.
 */
object SpotifyFeedResolver {

    private const val CACHE_DIR = "podcast-feeds"
    private const val TIMEOUT_MS = 20_000

    fun feedForShow(context: Context, showId: String): String? {
        val cache = File(File(context.filesDir, CACHE_DIR).apply { mkdirs() }, "show-$showId.txt")
        if (cache.exists()) return cache.readText().trim().ifBlank { null }

        val title = showTitle(showId) ?: return null
        val feed = searchFeed(title)
        if (feed == null) {
            Logger.w("Podcast", "No feed found for show", mapOf("show" to showId, "title" to title))
            return null
        }
        cache.writeText(feed)
        Logger.i("Podcast", "Resolved Spotify show to feed", mapOf(
            "show" to showId, "title" to title, "feed" to feed,
        ))
        return feed
    }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 YTMTrigger/1.0")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        Logger.w("Podcast", "Fetch failed", mapOf("url" to url), t = t)
        null
    }

    private val OG_TITLE = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"")

    private fun showTitle(showId: String): String? {
        val html = get("https://open.spotify.com/show/$showId") ?: return null
        val raw = OG_TITLE.find(html)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace("&amp;", "&").replace("&#x27;", "'").trim().ifBlank { null }
    }

    private fun searchFeed(title: String): String? {
        val term = URLEncoder.encode(title, "UTF-8")
        val json = get("https://itunes.apple.com/search?term=$term&entity=podcast&limit=5")
            ?: return null
        return try {
            val results = JSONObject(json).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val feed = results.getJSONObject(i).optString("feedUrl")
                if (feed.isNotBlank()) return feed
            }
            null
        } catch (t: Throwable) {
            Logger.w("Podcast", "Search parse failed", t = t)
            null
        }
    }
}
