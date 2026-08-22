package com.jasonschoenbrun.ytmtrigger.podcast

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** One playable podcast episode. */
data class Episode(
    val title: String,
    val audioUrl: String,
    val publishedMs: Long,
)

/**
 * Reads a podcast's episode list from its RSS feed.
 *
 * Chosen over the Spotify Web API because that requires a Premium account and
 * the public show page exposes only twelve episodes, whereas the same show's
 * feed carries the entire back catalogue - 384 episodes for the feed this was
 * built against - with no account, key or quota.
 *
 * Feeds are cached on disk: they are large (that one is 2.3 MB) and change at
 * most daily, so re-fetching on every trigger would waste data and add a
 * failure mode at exactly the moment playback needs to start.
 */
object PodcastCatalog {

    private const val CACHE_DIR = "podcast-feeds"
    private const val CACHE_TTL_MS = 12L * 60 * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Episodes for [feedUrl], newest first.
     *
     * Returns an empty list rather than throwing: a trigger that cannot reach
     * the network should surface as "no episodes" and be logged, not crash the
     * playback service.
     */
    fun episodes(context: Context, feedUrl: String, forceRefresh: Boolean = false): List<Episode> {
        val xml = feedXml(context, feedUrl, forceRefresh) ?: return emptyList()
        return try {
            parse(xml).sortedByDescending { it.publishedMs }
        } catch (t: Throwable) {
            Logger.e("Podcast", "Feed parse failed", mapOf("feed" to feedUrl), t = t)
            emptyList()
        }
    }

    private fun feedXml(context: Context, feedUrl: String, forceRefresh: Boolean): String? {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        val cache = File(dir, feedUrl.hashCode().toString() + ".xml")
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS
        if (fresh && !forceRefresh) return cache.readText()

        return try {
            val conn = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "YTMTrigger/1.0")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            cache.writeText(body)
            Logger.i("Podcast", "Feed fetched", mapOf(
                "feed" to feedUrl,
                "bytes" to body.length.toString(),
            ))
            body
        } catch (t: Throwable) {
            Logger.w("Podcast", "Feed fetch failed", mapOf("feed" to feedUrl), t = t)
            // A stale cache beats no episode at all.
            if (cache.exists()) cache.readText() else null
        }
    }

    private val DATE_SHAPES = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
    )

    private fun parseDate(text: String): Long {
        for (pattern in DATE_SHAPES) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(text.trim())?.time ?: continue
            } catch (_: Throwable) { /* try the next shape */ }
        }
        return 0L
    }

    private fun parse(xml: String): List<Episode> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val out = mutableListOf<Episode>()
        var inItem = false
        var title: String? = null
        var audio: String? = null
        var published = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> { inItem = true; title = null; audio = null; published = 0L }
                    // Only the first <title> in an item: feeds also carry
                    // itunes:title, and taking the last would sometimes win.
                    "title" -> if (inItem && title == null) title = parser.nextText().trim()
                    "pubDate" -> if (inItem) published = parseDate(parser.nextText())
                    "enclosure" -> if (inItem && audio == null) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url").orEmpty()
                        if (url.isNotBlank() && (type.startsWith("audio") || type.isBlank())) {
                            audio = url
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    val a = audio
                    if (a != null) out += Episode(title ?: "(untitled)", a, published)
                    inItem = false
                }
            }
            event = parser.next()
        }
        return out
    }
}
