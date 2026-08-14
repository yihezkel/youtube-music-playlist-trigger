package com.jasonschoenbrun.ytmtrigger.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.delay

/**
 * Reports — and waits on — notification-listener access for
 * [MediaSessionListenerService].
 *
 * ## Why this matters
 * [MediaSessionProbe] answers "is YT Music actually playing right now" via
 * [android.media.session.MediaSessionManager.getActiveSessions], which throws
 * `SecurityException` unless the caller owns an *approved* notification
 * listener. Without it, every `mediaSessionTimeline` entry in a
 * [com.jasonschoenbrun.ytmtrigger.diag.SelfTestRunRecord] reads `Unavailable`
 * and playback detection falls back to `AudioManager.isMusicActive`, which is
 * true for *any* audio focus holder — not just YouTube Music.
 *
 * ## Why this cannot auto-heal (unlike the accessibility service)
 * It is tempting to reuse the
 * [com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer]
 * trick and write the component into the `enabled_notification_listeners`
 * secure setting using `WRITE_SECURE_SETTINGS`. **That does not work, and it
 * is actively misleading.**
 *
 * Since Android 8, `NotificationManagerService` keeps listener approvals in
 * its own state (`ManagedServices.mApproved`, persisted to
 * `notification_policy.xml`) and only *writes back* to the secure setting for
 * compatibility; it does not read the setting back. Writing the setting
 * therefore changes nothing except the setting itself — verified on device:
 * after the write, `settings get secure enabled_notification_listeners`
 * listed this app, while `dumpsys notification` still showed
 * `Allowed notification listeners:` without it and `getActiveSessions` kept
 * throwing. Any check based on parsing that setting reports a false
 * positive, which is why [isEnabled] asks the framework instead.
 *
 * The app-facing API that *would* work,
 * `NotificationManager.setNotificationListenerAccessGranted`, is `@SystemApi`
 * gated behind `MANAGE_NOTIFICATION_LISTENERS` (`signature|installer`), so it
 * is neither compilable against the public SDK nor grantable by `pm grant` —
 * unlike `WRITE_SECURE_SETTINGS`, which carries the `development` protection
 * flag and is why the accessibility auto-heal works.
 *
 * So this is a genuine one-time setup step, in the same spirit as the
 * existing adb grant. See [adbAllowCommand].
 */
object NotifListenerEnforcer {

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, MediaSessionListenerService::class.java)

    /**
     * Whether NotificationManagerService has approved our listener.
     *
     * Deliberately asks the framework rather than parsing
     * `enabled_notification_listeners`: the setting can disagree with the
     * real approval state (see the class KDoc).
     */
    fun isEnabled(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(listenerComponent(context)) == true
    } catch (_: Throwable) {
        false
    }

    /** Approved by the OS *and* actually bound, so the probe can be used. */
    fun isConnected(): Boolean = MediaSessionListenerService.isListenerConnected()

    /**
     * The one-time command that grants notification-listener access.
     *
     * `cmd notification allow_listener` runs inside the shell uid and calls
     * into NotificationManagerService directly, which is the only path that
     * updates the real approval list.
     */
    fun adbAllowCommand(context: Context): String =
        "adb shell cmd notification allow_listener ${listenerComponent(context).flattenToString()}"

    /**
     * Waits up to [bindTimeoutMs] for an already-approved listener to bind.
     *
     * Binding is asynchronous, so right after boot or a fresh install the
     * approval can be in place before the service is connected. Returns
     * false immediately when access has not been granted at all — there is
     * nothing to wait for in that case.
     */
    suspend fun awaitConnected(
        context: Context,
        bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
        pollIntervalMs: Long = 150L,
    ): Boolean {
        if (isConnected()) return true
        if (!isEnabled(context)) return false
        val deadline = System.currentTimeMillis() + bindTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isConnected()) return true
            delay(pollIntervalMs)
        }
        return isConnected()
    }

    /**
     * Logs the current state once, including the fix command when access is
     * missing, so a log export always shows whether the MediaSession signal
     * was available for that session.
     */
    fun logState(context: Context) {
        if (isEnabled(context)) {
            Logger.i(
                "NotifPerm",
                "Notification listener approved",
                mapOf("connected" to isConnected().toString()),
            )
        } else {
            Logger.w(
                "NotifPerm",
                "Notification listener NOT approved — MediaSession probe unavailable, " +
                    "playback detection falls back to AudioManager.isMusicActive",
                mapOf("allow" to adbAllowCommand(context)),
            )
        }
    }

    const val DEFAULT_BIND_TIMEOUT_MS: Long = 4_000L
}
