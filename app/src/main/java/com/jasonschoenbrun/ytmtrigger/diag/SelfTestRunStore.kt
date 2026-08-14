package com.jasonschoenbrun.ytmtrigger.diag

import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists [SelfTestRunRecord]s as JSONL under
 * `filesDir/selftest-history/YYYY-MM.jsonl` (one file per calendar month).
 * Files older than [RETENTION_MS] are pruned on the first access of each
 * session. Read/write methods are synchronous; callers (always background
 * receivers / services) are expected to call them off the main thread.
 *
 * The store is intentionally append-only and uses plain JSON Lines so it
 * can be inspected with `adb shell run-as ... cat` without any tooling.
 */
object SelfTestRunStore {

    private const val DIR_NAME = "selftest-history"
    private const val RETENTION_DAYS = 90L
    private const val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
    private val prettyJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Volatile private var pruned: Boolean = false

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun record(context: Context, run: SelfTestRunRecord) {
        try {
            maybePrune(context)
            val file = currentFile(context, run.startedAtMs)
            val line = json.encodeToString(run)
            FileWriter(file, true).use { w ->
                w.write(line)
                w.write("\n")
            }
            Logger.i("SelfTestStore", "Recorded run", mapOf(
                "runId" to run.runId,
                "outcome" to run.outcome::class.simpleName.orEmpty(),
                "attempts" to run.attempts.size.toString(),
                "file" to file.name,
            ))
        } catch (t: Throwable) {
            Logger.e("SelfTestStore", "Failed to record run", t = t)
        }
    }

    /**
     * Return up to [max] most-recent records, newest first. Skips any line
     * we cannot decode (forward-compat for schema additions).
     */
    fun recent(context: Context, max: Int): List<SelfTestRunRecord> {
        maybePrune(context)
        val dir = dir(context)
        val files = dir.listFiles()?.toList()
            ?.filter { it.isFile && it.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            ?: return emptyList()
        val out = ArrayList<SelfTestRunRecord>(max)
        for (f in files) {
            val lines = try { f.readLines() } catch (_: Throwable) { continue }
            // Read newest-first within each file.
            for (line in lines.asReversed()) {
                if (line.isBlank()) continue
                val rec = try { json.decodeFromString<SelfTestRunRecord>(line) } catch (_: Throwable) { null }
                if (rec != null) out += rec
                if (out.size >= max) return out
            }
        }
        return out
    }

    /**
     * Export up to [max] most-recent records as a single pretty-printed JSON
     * file under cacheDir/exports, suitable for sharing via FileProvider.
     */
    fun exportRecentAsJson(context: Context, max: Int, appVersion: String): File? {
        return try {
            val runs = recent(context, max)
            val payload = ExportPayload(
                exportedAtMs = System.currentTimeMillis(),
                appVersion = appVersion,
                runCount = runs.size,
                runs = runs,
            )
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
            val out = File(exportDir, "ytmtrigger-selftest-runs-$ts.json")
            out.writeText(prettyJson.encodeToString(payload))
            out
        } catch (t: Throwable) {
            Logger.e("SelfTestStore", "Failed to export runs", t = t)
            null
        }
    }

    private fun currentFile(context: Context, atMs: Long): File {
        val name = monthFmt.format(Date(atMs)) + ".jsonl"
        return File(dir(context), name)
    }

    private fun maybePrune(context: Context) {
        if (pruned) return
        pruned = true
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            dir(context).listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Throwable) {
            // Pruning is best-effort.
        }
    }

    @kotlinx.serialization.Serializable
    private data class ExportPayload(
        val exportedAtMs: Long,
        val appVersion: String,
        val runCount: Int,
        val runs: List<SelfTestRunRecord>,
    )
}
