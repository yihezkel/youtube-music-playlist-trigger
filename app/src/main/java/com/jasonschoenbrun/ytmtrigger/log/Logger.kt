package com.jasonschoenbrun.ytmtrigger.log

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

enum class LogLevel(val short: String, val priority: Int) {
    DEBUG("D", Log.DEBUG), INFO("I", Log.INFO), WARN("W", Log.WARN), ERROR("E", Log.ERROR)
}

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val throwable: String? = null,
) {
    fun format(): String {
        val ts = TS_FMT.get()!!.format(Date(timestampMs))
        val f = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val t = if (throwable != null) "\n$throwable" else ""
        return "$ts ${level.short} $tag: $message$f$t"
    }

    companion object {
        private val TS_FMT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    }
}

object Logger {
    private const val TAG_PREFIX = "YTMT"
    private const val RING_CAPACITY = 2000
    private const val RETENTION_DAYS = 14L
    private const val RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000

    private val ring = ArrayDeque<LogEntry>(RING_CAPACITY)
    private val ringLock = Any()
    private val pending = ConcurrentLinkedQueue<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logsDir: File? = null
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        if (logsDir != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logsDir = dir
        scope.launch { pruneOldFiles() }
        scope.launch { writerLoop() }
        i("Logger", "Logger initialized", mapOf("dir" to dir.absolutePath))
    }

    fun d(tag: String, msg: String, fields: Map<String, String> = emptyMap()) =
        log(LogLevel.DEBUG, tag, msg, fields, null)
    fun i(tag: String, msg: String, fields: Map<String, String> = emptyMap()) =
        log(LogLevel.INFO, tag, msg, fields, null)
    fun w(tag: String, msg: String, fields: Map<String, String> = emptyMap(), t: Throwable? = null) =
        log(LogLevel.WARN, tag, msg, fields, t)
    fun e(tag: String, msg: String, fields: Map<String, String> = emptyMap(), t: Throwable? = null) =
        log(LogLevel.ERROR, tag, msg, fields, t)

    private fun log(level: LogLevel, tag: String, msg: String, fields: Map<String, String>, t: Throwable?) {
        val entry = LogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg,
            fields = fields,
            throwable = t?.let { stack(it) },
        )
        Log.println(level.priority, "$TAG_PREFIX/$tag", entry.format().substringAfter(": "))
        synchronized(ringLock) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(entry)
            _entries.value = ring.toList()
        }
        pending.add(entry)
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private suspend fun writerLoop() {
        while (true) {
            val toWrite = mutableListOf<LogEntry>()
            while (true) {
                val e = pending.poll() ?: break
                toWrite.add(e)
            }
            if (toWrite.isNotEmpty()) {
                runCatching {
                    val dir = logsDir ?: return@runCatching
                    val today = fileFmt.format(Date())
                    val file = File(dir, "$today.log")
                    FileWriter(file, true).use { w ->
                        for (e in toWrite) {
                            w.write(e.format())
                            w.write("\n")
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(500)
        }
    }

    private fun pruneOldFiles() {
        val dir = logsDir ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(ringLock) { ring.toList() }

    fun exportLatestFile(context: Context): File? {
        val dir = logsDir ?: return null
        // Flush pending synchronously
        val toWrite = mutableListOf<LogEntry>()
        while (true) {
            val e = pending.poll() ?: break
            toWrite.add(e)
        }
        val today = fileFmt.format(Date())
        val src = File(dir, "$today.log")
        if (toWrite.isNotEmpty()) {
            FileWriter(src, true).use { w ->
                for (e in toWrite) { w.write(e.format()); w.write("\n") }
            }
        }
        if (!src.exists()) return null
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(exportDir, "ytmtrigger-$today.log")
        src.copyTo(out, overwrite = true)
        return out
    }
}
