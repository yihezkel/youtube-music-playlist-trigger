package com.jasonschoenbrun.ytmtrigger.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

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
            // Bound but inert. Try to recover in place; a process restart is
            // not permitted here because a trigger or self-test is in flight.
            if (recoverIfUnresponsive(context, allowProcessRestart = false)) return true
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
     * Restart the process after a self-test failed with the accessibility
     * service never doing anything.
     *
     * ## What is actually known
     * Twice — 2026-08-14 18:35 and 2026-08-19 22:47, both shortly after the
     * package was replaced and the service re-enabled — a self-test timed out
     * on all three strategies with `ytmCameToForeground=false` and
     * `a11yStarted=false`, meaning the service received no window event at
     * all. Both times a later run, after the process had restarted, succeeded
     * in a few seconds.
     *
     * ## What is not known
     * The cause. Deliberate attempts to reproduce it all failed: reinstalling
     * the package, reinstalling with the post-update restart suppressed, and
     * dropping the service from `ENABLED_ACCESSIBILITY_SERVICES` so auto-heal
     * rewrote it — every one of those was followed by a passing self-test. So
     * this is not wired to package replacement or to the auto-heal write,
     * because neither was shown to cause it.
     *
     * (An earlier round of testing appeared to reproduce it readily by opening
     * an unrelated app and watching for events. That was a broken experiment:
     * `accessibility_service_config.xml` sets
     * `packageNames="com.google.android.apps.youtube.music"`, so the service
     * is *supposed* to ignore every other app, and the absence of events meant
     * nothing.)
     *
     * ## Why restarting here is still defensible
     * This runs only once the run has already failed and the audible alert has
     * already fired, so there is nothing left to interrupt, and the evidence
     * has already been persisted and uploaded. A restart is the only thing
     * ever observed to restore the service, and leaving the process in a state
     * where the accessibility service does nothing means the next real
     * scheduled trigger fails too. The guard on "no accessibility activity in
     * any attempt" keeps it away from ordinary failures such as YouTube Music
     * being slow or a layout change breaking the Play button, which a restart
     * would not fix.
     *
     * @return true if a restart was armed and the caller should stop work.
     */
    fun restartAfterDeadRun(context: Context, noA11yActivity: Boolean): Boolean {
        if (!noA11yActivity) return false
        if (!isAccessibilityEnabled(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RESTART, 0L)
        val now = System.currentTimeMillis()
        // A restart that did not help must not become a restart loop.
        if (now - last < RESTART_COOLDOWN_MS) {
            Logger.w(
                "A11yPerm",
                "Self-test failed with no accessibility activity, but a restart was already " +
                    "tried recently — not restarting again",
                mapOf("minsSinceLast" to ((now - last) / 60000).toString()),
            )
            return false
        }
        prefs.edit().putLong(KEY_LAST_RESTART, now).commit()
        Logger.w(
            "A11yPerm",
            "Self-test failed with no accessibility activity at all — restarting the process, " +
                "the only recovery ever observed for this state",
        )
        ProcessRestartReceiver.arm(context)
        return true
    }

    /**
     * Recover an accessibility service that is bound but not working.
     *
     * ## The failure this exists for
     * After the package is replaced (`adb install -r`, or a release update),
     * AccessibilityManagerService rebinds the service and `onServiceConnected`
     * fires — but the binding is inert: no `AccessibilityEvent` is ever
     * delivered and `rootInActiveWindow` returns null. Reproduced on
     * 2026-08-14 and again on 2026-08-19, where a self-test immediately after
     * reinstall timed out on all three strategies with zero window events,
     * while a run a few minutes later — after the process had restarted —
     * succeeded in 3.3s.
     *
     * Note this is *not* caused by the secure-settings auto-heal write: the
     * 2026-08-19 reproduction kept the service listed in
     * `ENABLED_ACCESSIBILITY_SERVICES` throughout, so [ensureEnabled] was a
     * no-op and the binding was still dead. The package replacement itself is
     * what breaks it.
     *
     * ## Why a ladder
     * Rewriting the secure setting to drop and re-add the component was tested
     * first and does **not** help: it produces a genuine unbind/rebind
     * (`Service destroyed` then `Service connected`) and the new instance is
     * just as inert, because the fault lives in the process, not the binding.
     * So this escalates:
     *
     *  1. Recreate the component. An app may always change its own components,
     *     and this makes the framework construct a fresh service instance.
     *  2. Restart the process, which is the only thing observed to work.
     *
     * [allowProcessRestart] must be false while a trigger or self-test is in
     * flight — killing the process there would abandon the very playback we
     * are trying to start. Call it with true only from app startup, where
     * nothing is in progress.
     *
     * @return true if the service is usable when this returns. When the
     *   process is restarted this does not return at all.
     */
    suspend fun recoverIfUnresponsive(
        context: Context,
        allowProcessRestart: Boolean = false,
    ): Boolean {
        if (!YtmAccessibilityService.isRunning()) return false
        if (YtmAccessibilityService.canReadActiveWindow()) return true

        Logger.w(
            "A11yPerm",
            "A11y bound but unresponsive — starting recovery",
            mapOf("allowProcessRestart" to allowProcessRestart.toString()),
        )

        // Rung 1: recreate the service component.
        runCatching { recreateComponent(context) }
            .onFailure { Logger.w("A11yPerm", "Component recreate failed", t = it) }
        // Disabling a component drops it from the enabled list, so re-assert.
        ensureEnabled(context)
        if (awaitUsable(COMPONENT_RECOVERY_TIMEOUT_MS)) {
            Logger.i("A11yPerm", "Recovered via component recreate")
            return true
        }

        if (!allowProcessRestart) {
            Logger.e(
                "A11yPerm",
                "A11y still unresponsive and a process restart is unsafe here; " +
                    "playback will likely fail until the app restarts",
            )
            return false
        }

        Logger.w("A11yPerm", "Component recreate insufficient — restarting process")
        ProcessRestartReceiver.arm(context)
        // Give the logger's writer loop a moment to flush before we go, or the
        // evidence for why we restarted is lost with the process.
        delay(700)
        exitProcess(0)
    }

    /**
     * Disable then re-enable our own accessibility service component so the
     * framework builds a new instance of it.
     */
    private suspend fun recreateComponent(context: Context) {
        val pm = context.packageManager
        val comp = ComponentName(context, YtmAccessibilityService::class.java)
        pm.setComponentEnabledSetting(
            comp,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        delay(600)
        pm.setComponentEnabledSetting(
            comp,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        Logger.i("A11yPerm", "Recreated A11y service component")
    }

    private suspend fun awaitUsable(timeoutMs: Long, pollIntervalMs: Long = 200L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (YtmAccessibilityService.isRunning() &&
                YtmAccessibilityService.canReadActiveWindow()
            ) return true
            delay(pollIntervalMs)
        }
        return false
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

    private const val PREFS = "a11y_enforcer"
    private const val KEY_LAST_RESTART = "lastProcessRestartMs"
    /** Long enough that a restart which did not help cannot loop. */
    private const val RESTART_COOLDOWN_MS = 6L * 60 * 60 * 1000

    const val DEFAULT_BIND_TIMEOUT_MS: Long = 4_000L
    /** Long enough for the framework to tear down and rebuild the service. */
    const val COMPONENT_RECOVERY_TIMEOUT_MS: Long = 6_000L
}
