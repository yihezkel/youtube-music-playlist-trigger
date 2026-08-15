package com.jasonschoenbrun.ytmtrigger.remote

import com.jasonschoenbrun.ytmtrigger.data.Schedule
import kotlinx.serialization.Serializable

/**
 * The subset of app configuration that may be changed remotely.
 *
 * Every field is nullable and means "leave whatever the device already has".
 * That matters because [com.jasonschoenbrun.ytmtrigger.data.AppSettings] mixes
 * user-editable configuration with device-reported state
 * (`lastSelfTestSuccessMs` and friends). If the console wrote the whole
 * settings object back, a stale editor tab would silently erase self-test
 * history. Partial updates make that impossible.
 */
@Serializable
data class RemoteConfig(
    val defaultPlaylistUrls: List<String>? = null,
    val defaultVolumePercent: Int? = null,
    val defaultEnableShuffle: Boolean? = null,
    val defaultSkipFirstTrack: Boolean? = null,
    val selfTestEnabled: Boolean? = null,
    val israeliObservance: Boolean? = null,
    val selfTestPlaylistUrl: String? = null,
    /** Full replacement for the schedule list when present. */
    val schedules: List<Schedule>? = null,
)

/**
 * What the device publishes about itself, so the console can show health
 * without the phone being reachable.
 */
@Serializable
data class RemoteState(
    val appVersionName: String,
    val appVersionCode: Long,
    val deviceModel: String,
    val androidSdk: Int,
    val updatedAtMs: Long,
    val accessibilityHealthy: Boolean,
    val notificationListenerReady: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val lastSelfTestSuccessMs: Long,
    val lastSelfTestSuccessStrategy: String?,
    val lastSelfTestFailureMs: Long,
    val lastSelfTestFailureReason: String?,
    val lastSelfTestSkipMs: Long,
    val lastSelfTestSkipReason: String?,
    val scheduleCount: Int,
    /** Config revision the device has actually applied. */
    val appliedConfigRevision: Long,
)

/** Commands the console can queue for the device. */
object RemoteCommands {
    const val PLAY_NOW = "playNow"
    const val RUN_SELF_TEST = "runSelfTest"
    const val UPLOAD_LOGS = "uploadLogs"

    const val STATUS_PENDING = "pending"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
}
