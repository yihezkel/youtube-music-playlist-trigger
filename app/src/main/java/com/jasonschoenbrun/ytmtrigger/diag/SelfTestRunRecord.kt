package com.jasonschoenbrun.ytmtrigger.diag

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured forensic record of a single self-test run. Persisted as JSONL
 * by [SelfTestRunStore] so the UI can drill into a single failure and so the
 * user can export the full record (intent dispatch, A11y steps, foreground
 * transitions, MediaSession timeline, full diagnostics) for support.
 *
 * Every field is intentionally `Serializable` so the entire record can be
 * round-tripped through [kotlinx.serialization.json].
 */
@Serializable
data class SelfTestRunRecord(
    val runId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** "scheduled" | "manual" | "boot-recovery" */
    val trigger: String,
    val outcome: RunOutcome,
    val playlistId: String?,
    val attempts: List<StrategyAttempt>,
    val preflight: DiagnosticsSnapshotData,
    val postFailure: DiagnosticsSnapshotData?,
    val appVersionName: String,
    val appVersionCode: Long,
    val ytmVersionName: String?,
    val ytmVersionCode: Long?,
)

@Serializable
sealed class RunOutcome {
    @Serializable @SerialName("skipped")
    data class Skipped(val reason: String) : RunOutcome()

    @Serializable @SerialName("success")
    data class Success(val strategy: String, val elapsedMs: Long) : RunOutcome()

    @Serializable @SerialName("allFailed")
    data class AllFailed(val tried: List<String>, val summary: String) : RunOutcome()

    @Serializable @SerialName("crash")
    data class Crash(val message: String) : RunOutcome()

    @Serializable @SerialName("configError")
    data class ConfigError(val message: String) : RunOutcome()
}

@Serializable
data class StrategyAttempt(
    val strategy: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val outcome: AttemptOutcome,
    /** True if the launch intent dispatched without throwing. */
    val intentDispatchOk: Boolean,
    val intentException: String?,
    /** True if YT Music was observed as foreground at any sample during the wait. */
    val ytmCameToForeground: Boolean,
    /** Final foreground package observed at end of the attempt. */
    val foregroundAppAtEnd: String?,
    /** (elapsedMs, MediaSessionProbe.Status simpleName) samples during the wait. */
    val mediaSessionTimeline: List<TimelineSample>,
    /** (elapsedMs, AudioManager.isMusicActive) samples during the wait. */
    val audioActiveTimeline: List<TimelineSample>,
    val a11yActionResult: A11yActionResult?,
    /** Cheap re-snapshot of system signals taken once per attempt. */
    val perAttemptDiagnostics: DiagnosticsSnapshotData,
)

@Serializable
enum class AttemptOutcome {
    Succeeded,
    TimedOut,
    ThrewException,
}

@Serializable
data class TimelineSample(
    val elapsedMs: Long,
    val value: String,
)

/**
 * Structured result returned by [com.jasonschoenbrun.ytmtrigger.accessibility
 * .YtmAccessibilityService] for a single [com.jasonschoenbrun.ytmtrigger
 * .accessibility.PostLaunchAction]. The service already logs each step; this
 * is the same data in a form attachable to a [SelfTestRunRecord].
 */
@Serializable
data class A11yActionResult(
    /** True if the action coroutine ran to completion (independent of step success). */
    val completed: Boolean,
    /** True if the action coroutine was ever started by an A11y event. */
    val started: Boolean,
    val totalDurationMs: Long,
    val steps: List<A11yStep>,
    /** Foreground package observed before runAction started. Useful when the
     *  service never fired because the wrong package was foreground. */
    val foregroundPkgAtStart: String?,
    /** Last-seen exception message during the action, or null. */
    val errorMessage: String?,
)

@Serializable
data class A11yStep(
    /** "PressPlay" | "PressPlayRetry" | "DismissDialog" | "Shuffle" | "ShuffleRetry"
     *  | "Skip" | "SkipRetry" | "WaitForActivePlayback" | "EnsureForeground:<step>" */
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val ok: Boolean,
    /** Free-form per-step detail (e.g. selector that matched, title before/after). */
    val detail: String? = null,
)

/**
 * Structured form of what [DiagnosticsSnapshot.capture] logs line-by-line.
 * Every nested object is nullable to tolerate partial failures during
 * capture — we never want diagnostic-capture errors to mask the test result.
 */
@Serializable
data class DiagnosticsSnapshotData(
    val capturedAtMs: Long,
    val origin: String,
    val ytmPackage: PackageInfoData? = null,
    val power: PowerStateData? = null,
    val standbyBucket: String? = null,
    val batteryWhitelist: Boolean? = null,
    val network: NetworkData? = null,
    val audio: AudioStateData? = null,
    val a11y: A11yStateData? = null,
    val notifListener: NotifListenerData? = null,
    val fullScreenIntentAllowed: Boolean? = null,
    val resolveActivity: ResolveActivityData? = null,
    val foregroundApp: String? = null,
    val mediaSession: String? = null,
    /** 0-100 if known, else null. */
    val batteryLevelPct: Int? = null,
    /** True if connected to power (AC/USB/wireless). */
    val charging: Boolean? = null,
    /** Compact textual list of active audio-playback configurations. */
    val activePlayers: List<ActivePlayerData> = emptyList(),
)

@Serializable
data class PackageInfoData(
    val versionName: String?,
    val versionCode: Long,
    val lastUpdateMs: Long,
    val enabledSetting: Int,
)

@Serializable
data class PowerStateData(
    val isInteractive: Boolean,
    val isDeviceIdleMode: Boolean,
    val isPowerSaveMode: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
)

@Serializable
data class NetworkData(
    val hasActiveNetwork: Boolean,
    val validated: Boolean,
    val internet: Boolean,
    val wifi: Boolean,
    val cellular: Boolean,
    val metered: Boolean,
)

@Serializable
data class AudioStateData(
    val mode: Int,
    val modeName: String,
    val isMusicActive: Boolean,
    val isBluetoothA2dpOn: Boolean,
    val isWiredHeadsetOn: Boolean,
    val streamMusicVol: Int,
    val streamMusicMax: Int,
    val outputDevices: List<String>,
)

@Serializable
data class A11yStateData(
    val enabledInSettings: Boolean,
    val serviceBound: Boolean,
)

@Serializable
data class NotifListenerData(
    val enabledInSettings: Boolean,
    val serviceConnected: Boolean,
)

@Serializable
data class ResolveActivityData(
    val resolved: Boolean,
    val activity: String?,
)

/**
 * One active audio player as reported by [android.media.AudioManager].
 *
 * The owning package/uid is deliberately absent: `AudioPlaybackConfiguration`
 * only exposes `getAudioAttributes()` in the public SDK — `getClientUid()` and
 * `getClientPid()` are `@SystemApi @hide`. Attribution therefore has to come
 * from the MediaSession probe instead.
 */
@Serializable
data class ActivePlayerData(
    val usage: Int,
    val contentType: Int,
)
