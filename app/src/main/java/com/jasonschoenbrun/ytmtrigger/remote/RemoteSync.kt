package com.jasonschoenbrun.ytmtrigger.remote

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.NotifListenerEnforcer
import com.jasonschoenbrun.ytmtrigger.selftest.SelfTestReceiver
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Pulls remote configuration, publishes device state, and runs queued
 * commands.
 *
 * ## Why polling rather than push
 * A Firestore snapshot listener only fires while this process is alive, and
 * the app is normally killed between alarms. Real push would need FCM, and
 * sending FCM securely needs a server component (Cloud Functions, which
 * requires the paid Blaze plan) — a lot of moving parts and cost for a
 * personal device. Instead [RemotePollWorker] syncs periodically and every
 * trigger/self-test syncs opportunistically, so config lands within minutes
 * and costs nothing.
 *
 * ## Config is applied field-by-field
 * Only fields present in [RemoteConfig] are applied, and device-owned state
 * is preserved (see [applyConfig]).
 */
object RemoteSync {

    private const val PREFS = "remote_sync"
    private const val KEY_APPLIED_REVISION = "appliedConfigRevision"
    /** Firestore's hard limit is ~1 MiB per document; stay clear of it. */
    private const val MAX_LOG_CHARS = 900_000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * One full sync cycle. Safe to call often; a no-op when remote control is
     * not configured or not signed in.
     *
     * @return true if a cycle actually ran.
     */
    suspend fun syncOnce(context: Context, reason: String): Boolean {
        val uid = RemoteGate.signedInUid(context) ?: return false
        return try {
            val device = deviceDoc(context, uid)
            pullAndApplyConfig(context, device)
            pushState(context, device)
            runPendingCommands(context, device)
            Logger.d("Remote", "Sync complete", mapOf("reason" to reason))
            true
        } catch (t: Throwable) {
            Logger.w("Remote", "Sync failed", mapOf("reason" to reason), t = t)
            false
        }
    }

    // --- config ---------------------------------------------------------

    private suspend fun pullAndApplyConfig(context: Context, device: DocumentReference) {
        val snap = device.collection("data").document("config").get().await()
        if (!snap.exists()) return
        val revision = snap.getLong("revision") ?: 0L
        val applied = prefs(context).getLong(KEY_APPLIED_REVISION, -1L)
        if (revision <= applied) return
        val raw = snap.getString("json").orEmpty()
        if (raw.isBlank()) return
        val config = runCatching { json.decodeFromString<RemoteConfig>(raw) }
            .onFailure { Logger.e("Remote", "Bad remote config JSON", t = it) }
            .getOrNull() ?: return
        applyConfig(context, config)
        prefs(context).edit().putLong(KEY_APPLIED_REVISION, revision).apply()
        Logger.i("Remote", "Applied remote config", mapOf(
            "revision" to revision.toString(),
            "schedules" to (config.schedules?.size?.toString() ?: "unchanged"),
            "playlists" to (config.defaultPlaylistUrls?.size?.toString() ?: "unchanged"),
        ))
    }

    private fun applyConfig(context: Context, config: RemoteConfig) {
        SettingsRepository.get(context).update { s ->
            s.copy(
                defaultPlaylistUrls = config.defaultPlaylistUrls ?: s.defaultPlaylistUrls,
                defaultVolumePercent = config.defaultVolumePercent ?: s.defaultVolumePercent,
                defaultEnableShuffle = config.defaultEnableShuffle ?: s.defaultEnableShuffle,
                defaultSkipFirstTrack = config.defaultSkipFirstTrack ?: s.defaultSkipFirstTrack,
                selfTestEnabled = config.selfTestEnabled ?: s.selfTestEnabled,
                israeliObservance = config.israeliObservance ?: s.israeliObservance,
                selfTestPlaylistUrl = config.selfTestPlaylistUrl ?: s.selfTestPlaylistUrl,
            )
        }
        val incoming = config.schedules ?: return
        val repo = ScheduleRepository.get(context)
        val existing = repo.all().associateBy { it.id }
        // Keep the rotation history: it lives on Schedule but is device state,
        // and the console has no reason to know about it. Without this, every
        // remote edit would reset "don't repeat the last 3 playlists".
        val merged = incoming.map { s ->
            if (s.lastPickedPlaylistIds.isEmpty()) {
                s.copy(lastPickedPlaylistIds = existing[s.id]?.lastPickedPlaylistIds ?: emptyList())
            } else s
        }
        val keepIds = merged.map { it.id }.toSet()
        existing.keys.filterNot { it in keepIds }.forEach { repo.delete(it) }
        merged.forEach { repo.upsert(it) }
        AlarmScheduler.rescheduleAll(context, merged)
        Logger.i("Remote", "Rescheduled after remote config", mapOf(
            "count" to merged.count { it.enabled }.toString(),
        ))
    }

    // --- state ----------------------------------------------------------

    private suspend fun pushState(context: Context, device: DocumentReference) {
        val settings = SettingsRepository.get(context).current()
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val pm = context.getSystemService(PowerManager::class.java)
        val state = RemoteState(
            appVersionName = pkg.versionName ?: "?",
            appVersionCode = pkg.longVersionCode,
            deviceModel = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
            updatedAtMs = System.currentTimeMillis(),
            accessibilityHealthy = YtmAccessibilityService.isResponsive(),
            notificationListenerReady = NotifListenerEnforcer.isEnabled(context),
            batteryOptimizationIgnored =
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
            lastSelfTestSuccessMs = settings.lastSelfTestSuccessMs,
            lastSelfTestSuccessStrategy = settings.lastSelfTestSuccessStrategy,
            lastSelfTestFailureMs = settings.lastSelfTestFailureMs,
            lastSelfTestFailureReason = settings.lastSelfTestFailureReason,
            lastSelfTestSkipMs = settings.lastSelfTestSkipMs,
            lastSelfTestSkipReason = settings.lastSelfTestSkipReason,
            scheduleCount = ScheduleRepository.get(context).all().size,
            appliedConfigRevision = prefs(context).getLong(KEY_APPLIED_REVISION, -1L),
        )
        device.set(
            mapOf(
                "json" to json.encodeToString(state),
                "updatedAtMs" to state.updatedAtMs,
                "deviceModel" to state.deviceModel,
                "appVersionName" to state.appVersionName,
            ),
        ).await()
    }

    // --- commands -------------------------------------------------------

    private suspend fun runPendingCommands(context: Context, device: DocumentReference) {
        val pending = device.collection("commands")
            .whereEqualTo("status", RemoteCommands.STATUS_PENDING)
            .orderBy("createdAtMs", Query.Direction.ASCENDING)
            .limit(10)
            .get()
            .await()
        for (doc in pending.documents) {
            val type = doc.getString("type").orEmpty()
            val result = runCatching { execute(context, type, doc.getString("arg")) }
            val ok = result.getOrNull() ?: false
            doc.reference.set(
                mapOf(
                    "status" to if (ok) RemoteCommands.STATUS_DONE else RemoteCommands.STATUS_FAILED,
                    "completedAtMs" to System.currentTimeMillis(),
                    "resultMessage" to (result.exceptionOrNull()?.message ?: if (ok) "ok" else "not executed"),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            ).await()
            Logger.i("Remote", "Command handled", mapOf("type" to type, "ok" to ok.toString()))
        }
    }

    private suspend fun execute(context: Context, type: String, arg: String?): Boolean = when (type) {
        RemoteCommands.PLAY_NOW -> {
            val scheduleId = arg ?: ScheduleRepository.get(context).all().firstOrNull()?.id
            if (scheduleId == null) false else {
                AlarmScheduler.triggerSoon(context, scheduleId)
                true
            }
        }
        RemoteCommands.RUN_SELF_TEST -> {
            SelfTestReceiver.fireManual(context)
            true
        }
        RemoteCommands.UPLOAD_LOGS -> {
            uploadLogs(context, days = arg?.toIntOrNull() ?: 3)
            true
        }
        else -> {
            Logger.w("Remote", "Unknown command", mapOf("type" to type))
            false
        }
    }

    // --- logs -----------------------------------------------------------

    /**
     * Upload the most recent [days] log files so they can be read from the
     * console. This is what removes the USB cable from the loop when
     * something breaks while the phone is out of reach.
     */
    suspend fun uploadLogs(context: Context, days: Int = 3): Int {
        val uid = RemoteGate.signedInUid(context) ?: return 0
        val dir = File(context.filesDir, "logs")
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?.take(days)
            .orEmpty()
        if (files.isEmpty()) return 0
        val device = deviceDoc(context, uid)
        var uploaded = 0
        for (f in files) {
            try {
                val text = f.readText()
                val truncated = text.length > MAX_LOG_CHARS
                val payload = if (truncated) text.takeLast(MAX_LOG_CHARS) else text
                device.collection("logs").document(f.name.removeSuffix(".log")).set(
                    mapOf(
                        "content" to payload,
                        "sizeBytes" to f.length(),
                        "truncated" to truncated,
                        "uploadedAtMs" to System.currentTimeMillis(),
                    ),
                ).await()
                uploaded++
            } catch (t: Throwable) {
                Logger.w("Remote", "Log upload failed", mapOf("file" to f.name), t = t)
            }
        }
        Logger.i("Remote", "Uploaded logs", mapOf("count" to uploaded.toString()))
        return uploaded
    }

    // --- plumbing -------------------------------------------------------

    private fun deviceDoc(context: Context, uid: String): DocumentReference =
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("devices").document(RemoteGate.deviceId(context))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
