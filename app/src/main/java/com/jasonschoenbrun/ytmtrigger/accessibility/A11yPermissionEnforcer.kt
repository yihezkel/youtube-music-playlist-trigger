package com.jasonschoenbrun.ytmtrigger.accessibility

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.delay

/**
 * Keeps the YTM Trigger Accessibility service enabled, including after Android
 * unilaterally disables it (which happens on some OEMs after reboot, after an
 * OS update, after the user clears app data, after a sideload-install of a new
 * version, or — more commonly — after the user toggles it off by accident in
 * the Accessibility settings).
 *
 * ## How
 * On Android, no normal app can grant itself the Accessibility permission —
 * that's a hard security boundary enforced by AccessibilityManagerService.
 * However, an app holding [android.Manifest.permission.WRITE_SECURE_SETTINGS]
 * can write to [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] directly,
 * which the framework treats as an enable. `WRITE_SECURE_SETTINGS` is a
 * `signature|privileged` permission; a sideloaded app cannot obtain it
 * through a runtime request. The user must grant it once via adb:
 *
 *     adb shell pm grant com.jasonschoenbrun.ytmtrigger \
 *         android.permission.WRITE_SECURE_SETTINGS
 *
 * After that one-time grant, this object can:
 *   * On every app start / boot / scheduled-trigger fire, re-enable our A11y
 *     service if Android disabled it.
 *   * Watch [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] while the app
 *     process is alive and re-enable immediately if anything removes us.
 *
 * If the user does NOT grant `WRITE_SECURE_SETTINGS`, this class just reports
 * the current enabled state and surfaces the adb command in logs / UI so the
 * user can either run the adb grant or toggle the service manually in
 * Settings → Accessibility → Installed apps → YTM Trigger Helper.
 *
 * ## Why not Device Owner / root?
 * Device Owner (`dpm set-device-owner`) is much more invasive — it requires
 * the device to have no other user accounts (essentially a factory reset)
 * and even then DevicePolicyManager's [setPermissionGrantState] is blocked
 * for `WRITE_SECURE_SETTINGS` on most builds. Root works but is not
 * realistic for a stock production device. The adb-grant approach used here
 * is the same mechanism used by widely-deployed automation apps such as
 * Tasker, Bouncer, AutoInput, etc., and is the most defensible
 * "no-user-intervention-after-setup" path on a normal stock Android phone.
 */
object A11yPermissionEnforcer {

    private const val SERVICE_FQN =
        "com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService"

    /** "<pkg>/<service-fqn>" — the canonical form for ENABLED_ACCESSIBILITY_SERVICES. */
    fun expectedServiceComponent(context: Context): String =
        "${context.packageName}/$SERVICE_FQN"

    /** True if the user has granted us WRITE_SECURE_SETTINGS via adb. */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True if our A11y service component is listed in the OS-enabled set. */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
        } catch (_: Throwable) {
            return false
        }
        val expected = expectedServiceComponent(context)
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * The exact adb command the user needs to run to grant the auto-heal
     * permission. Suitable for display in UI and copy-paste.
     */
    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Attempts to ensure our A11y service is enabled.
     *
     * Returns `true` if (after this call) the service is listed as enabled in
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. This does NOT
     * guarantee that `YtmAccessibilityService.isRunning()` is already true —
     * it can take a moment for AccessibilityManagerService to bind the
     * service after the setting flips. Callers that need to act
     * immediately should use [ensureEnabledAndBound] instead.
     *
     * If the service is already enabled, this is a no-op.
     * If WRITE_SECURE_SETTINGS is not granted, this logs a warning with the
     * grant command and returns the current (likely false) state.
     */
    fun ensureEnabled(context: Context): Boolean {
        if (isAccessibilityEnabled(context)) return true
        if (!hasWriteSecureSettings(context)) {
            Logger.w(
                "A11yPerm",
                "A11y service disabled and WRITE_SECURE_SETTINGS not granted; cannot self-heal",
                mapOf("grant" to adbGrantCommand(context)),
            )
            return false
        }
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val expected = expectedServiceComponent(context)
            val parts = current.split(":").filter { it.isNotBlank() }.toMutableList()
            if (parts.none { it.equals(expected, ignoreCase = true) }) {
                parts.add(expected)
            }
            val updated = parts.joinToString(":")
            val wroteList = Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated,
            )
            // The master switch must be on or the framework ignores the list.
            val wroteMaster = Settings.Secure.putInt(
                cr,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            Logger.i(
                "A11yPerm",
                "Auto-enabled A11y service via WRITE_SECURE_SETTINGS",
                mapOf(
                    "wroteList" to wroteList.toString(),
                    "wroteMaster" to wroteMaster.toString(),
                    "before" to current,
                    "after" to updated,
                ),
            )
            isAccessibilityEnabled(context)
        } catch (t: Throwable) {
            Logger.e("A11yPerm", "Auto-enable failed", t = t)
            false
        }
    }

    /**
     * Like [ensureEnabled], but also waits up to [bindTimeoutMs] for the
     * accessibility service to become genuinely usable — bound *and* able to
     * read the active window.
     *
     * Waiting on `isRunning()` alone was not enough: a service can bind and
     * report running while delivering no events and reading no windows, which
     * made this return true for a service that could not press Play. Polling
     * until it can read a window also absorbs the transient nulls that occur
     * during a window transition, so one bad sample can't cause a false alarm.
     *
     * Callers are background paths (trigger + self-test), which is required:
     * [YtmAccessibilityService.canReadActiveWindow] blocks on a binder call.
     *
     * Returns `true` only if the service is usable by the time this returns.
     */
    suspend fun ensureEnabledAndBound(
        context: Context,
        bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
        pollIntervalMs: Long = 150L,
    ): Boolean {
        fun usable() =
            YtmAccessibilityService.isRunning() && YtmAccessibilityService.canReadActiveWindow()

        if (usable()) return true
        ensureEnabled(context)
        val deadline = System.currentTimeMillis() + bindTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (usable()) return true
            delay(pollIntervalMs)
        }
        if (YtmAccessibilityService.isRunning()) {
            Logger.w(
                "A11yPerm",
                "A11y service is bound but unresponsive — it cannot read the active window, " +
                    "so it will not receive events or press Play. Use 'Restart service' on the " +
                    "Self-test screen to force a re-bind.",
                mapOf(
                    "timeoutMs" to bindTimeoutMs.toString(),
                    "sawEvents" to YtmAccessibilityService.isResponsive().toString(),
                ),
            )
        }
        return usable()
    }

    /**
     * Force AccessibilityManagerService to unbind and re-bind our service by
     * removing it from the enabled list and immediately re-adding it.
     *
     * This is the remediation for the "bound but unresponsive" state that
     * [YtmAccessibilityService.isResponsive] detects — a stale binding can't
     * be repaired from inside the service, only replaced. It is deliberately
     * user-initiated rather than automatic: the failure has been seen only
     * once and is not yet reproducible, so silently toggling the service the
     * app depends on would risk more than it fixes.
     *
     * The re-enable runs in a `finally` so an exception midway can never
     * leave the service disabled.
     */
    fun restart(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) {
            Logger.w(
                "A11yPerm",
                "Cannot restart A11y service: WRITE_SECURE_SETTINGS not granted",
                mapOf("grant" to adbGrantCommand(context)),
            )
            return false
        }
        try {
            val cr = context.contentResolver
            val expected = expectedServiceComponent(context)
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val without = current.split(":")
                .filter { it.isNotBlank() && !it.equals(expected, ignoreCase = true) }
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without.joinToString(":"),
            )
            Logger.i("A11yPerm", "Removed A11y service to force a re-bind", mapOf("before" to current))
        } catch (t: Throwable) {
            Logger.e("A11yPerm", "A11y restart: removal failed", t = t)
        } finally {
            runCatching { ensureEnabled(context) }
                .onFailure { Logger.e("A11yPerm", "A11y restart: re-enable failed", t = it) }
        }
        val ok = isAccessibilityEnabled(context)
        Logger.i("A11yPerm", "A11y restart finished", mapOf("enabled" to ok.toString()))
        return ok
    }

    /**
     * Registers a [ContentObserver] on the enabled-A11y-services secure
     * setting. Whenever the setting changes — typically because the user
     * toggled our service off in Settings, or Android rebooted us out of
     * the list — we re-enable ourselves (if the grant allows it).
     *
     * Call this once from `Application.onCreate`. Safe to call multiple
     * times; subsequent calls are no-ops.
     */
    @Synchronized
    fun startWatching(context: Context) {
        if (watching) return
        val appCtx = context.applicationContext
        if (!hasWriteSecureSettings(appCtx)) {
            // No point watching if we can't act. Still log so the user knows
            // they need to grant.
            Logger.i(
                "A11yPerm",
                "Not watching A11y setting: WRITE_SECURE_SETTINGS not granted",
                mapOf("grant" to adbGrantCommand(appCtx)),
            )
            return
        }
        try {
            val handler = Handler(Looper.getMainLooper())
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val enabledNow = isAccessibilityEnabled(appCtx)
                    if (!enabledNow) {
                        Logger.w(
                            "A11yPerm",
                            "A11y setting changed and our service is missing — re-enabling",
                        )
                        ensureEnabled(appCtx)
                    }
                }
            }
            appCtx.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                /* notifyForDescendants = */ false,
                observer,
            )
            watching = true
            Logger.i("A11yPerm", "Watching A11y setting for changes")
        } catch (t: Throwable) {
            Logger.e("A11yPerm", "Failed to register A11y setting observer", t = t)
        }
    }

    @Volatile private var watching = false

    const val DEFAULT_BIND_TIMEOUT_MS: Long = 4_000L
}
