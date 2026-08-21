package com.jasonschoenbrun.ytmtrigger.diag

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One line per occasion the app failed to play music, with a plain-language
 * reason.
 *
 * The information already existed but was not answerable: self-test outcomes
 * lived in [SelfTestRunStore] as large forensic records, trigger failures only
 * as notification text and log lines, and the settings held just the single
 * most recent failure. Answering "what went wrong this week" meant reading raw
 * logs. This keeps the small, human-readable subset needed to show that
 * directly, in the app and remotely.
 */
object FailureLog {

    @Serializable
    data class Entry(
        val atMs: Long,
        /** "self-test" or "trigger". */
        val kind: String,
        /** One sentence, written for a person rather than for grepping. */
        val reason: String,
    )

    const val KIND_SELF_TEST = "self-test"
    const val KIND_TRIGGER = "trigger"

    private const val DIR = "failures"
    private const val RETENTION_DAYS = 60L
    private val json = Json { ignoreUnknownKeys = true }
    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    private val lock = Any()

    fun record(context: Context, kind: String, reason: String) {
        val entry = Entry(System.currentTimeMillis(), kind, reason.trim().take(300))
        try {
            synchronized(lock) {
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                prune(dir)
                File(dir, "${monthFmt.format(Date(entry.atMs))}.jsonl")
                    .appendText(json.encodeToString(entry) + "\n")
            }
            Logger.i("FailureLog", "Recorded failure", mapOf("kind" to kind))
        } catch (t: Throwable) {
            // Never let bookkeeping break the caller's error handling.
            Logger.w("FailureLog", "Could not record failure", t = t)
        }
    }

    /**
     * Failures within the last [days], newest first.
     *
     * Self-test failures are derived from [SelfTestRunStore] rather than
     * written here, for two reasons: that store already holds 90 days of
     * outcomes, so history is available immediately instead of only from the
     * day this file was introduced, and keeping one writer per fact avoids the
     * same failure appearing twice. This file therefore holds trigger failures
     * only, and the two are merged on read.
     */
    fun recent(context: Context, days: Int = 7): List<Entry> {
        // Aligned to calendar days so the list matches the chart exactly.
        // A rolling 7x24h window let entries appear in the list that fell
        // outside the seven day-buckets drawn above it.
        val cutoff = startOfDay(System.currentTimeMillis()) - (days - 1) * 24L * 60 * 60 * 1000
        return (triggerFailures(context, cutoff) + selfTestFailures(context, cutoff))
            .sortedByDescending { it.atMs }
    }

    private fun triggerFailures(context: Context, cutoff: Long): List<Entry> = try {
        File(context.filesDir, DIR).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            // Two months always covers a week spanning a month boundary.
            ?.take(2)
            ?.flatMap { f -> f.readLines() }
            ?.mapNotNull { line ->
                line.takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.decodeFromString<Entry>(it) }.getOrNull() }
            }
            ?.filter { it.atMs >= cutoff }
            .orEmpty()
    } catch (t: Throwable) {
        Logger.w("FailureLog", "Could not read trigger failures", t = t)
        emptyList()
    }

    private fun selfTestFailures(context: Context, cutoff: Long): List<Entry> = try {
        SelfTestRunStore.recent(context, max = 200)
            .filter { it.endedAtMs >= cutoff }
            .mapNotNull { run ->
                val reason = when (val o = run.outcome) {
                    is RunOutcome.AllFailed ->
                        "Self-test could not start playback with any of ${o.tried.size} launch strategies (${o.tried.joinToString(", ")})."
                    is RunOutcome.Crash ->
                        "Self-test crashed before it could finish: ${o.message}"
                    is RunOutcome.ConfigError ->
                        "Self-test could not run because of a configuration problem: ${o.message}"
                    else -> null
                }
                reason?.let { Entry(run.endedAtMs, KIND_SELF_TEST, it) }
            }
    } catch (t: Throwable) {
        Logger.w("FailureLog", "Could not read self-test failures", t = t)
        emptyList()
    }

    /**
     * Failure counts for the last [days] days, oldest first, so a caller can
     * draw a simple per-day chart. Index 0 is the oldest day in the window and
     * the last index is today.
     */
    fun dailyCounts(entries: List<Entry>, days: Int = 7): List<Int> {
        val dayMs = 24L * 60 * 60 * 1000
        val todayStart = startOfDay(System.currentTimeMillis())
        return (0 until days).map { i ->
            val start = todayStart - (days - 1 - i) * dayMs
            entries.count { it.atMs >= start && it.atMs < start + dayMs }
        }
    }

    private fun startOfDay(ms: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000
        dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
    }
}
