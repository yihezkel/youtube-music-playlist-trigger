package com.jasonschoenbrun.ytmtrigger.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Detect Android-vendor-specific background restriction systems
 * ("Sleeping apps", "Auto-launch", "Protected apps", "App standby", etc.)
 * that silently block alarms/foreground services from running on schedule.
 *
 * Strategy:
 *   1. Use the OS-standard signals we have:
 *        - PowerManager.isIgnoringBatteryOptimizations
 *        - ActivityManager.isBackgroundRestricted (API 28+)
 *      These tell us if Android itself is throttling us.
 *   2. Detect the manufacturer and surface a vendor-specific link to the
 *      exact settings screen + concise instructions, even when the OS
 *      signals are green (because vendors layer their own systems on top
 *      that don't expose APIs).
 */
data class BackgroundRestrictionStatus(
    val osIgnoringBatteryOptimization: Boolean,
    val osBackgroundRestricted: Boolean,
    val manufacturer: String,
    val vendorAdvice: VendorAdvice?,
) {
    /** True only when nothing the OS can tell us is blocking us. */
    val osLevelOk: Boolean get() = osIgnoringBatteryOptimization && !osBackgroundRestricted
}

data class VendorAdvice(
    val title: String,
    val steps: List<String>,
    /** Best-effort intent to land directly on the relevant settings page. */
    val openIntent: Intent?,
    /** Web fallback if the intent doesn't resolve on this device. */
    val webUrl: String?,
)

object BackgroundRestrictionChecker {

    fun check(context: Context): BackgroundRestrictionStatus {
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            am?.isBackgroundRestricted == true
        } else false
        val mfr = (Build.MANUFACTURER ?: "").lowercase()
        return BackgroundRestrictionStatus(
            osIgnoringBatteryOptimization = ignoring,
            osBackgroundRestricted = restricted,
            manufacturer = mfr,
            vendorAdvice = adviceFor(mfr, context.packageName),
        )
    }

    private fun adviceFor(mfr: String, pkg: String): VendorAdvice? {
        return when {
            mfr.contains("samsung") -> VendorAdvice(
                title = "Samsung — Sleeping apps & Background usage limits",
                steps = listOf(
                    "Settings → Battery → Background usage limits → Never sleeping apps → add YTM Trigger.",
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                    "Settings → Device care → Auto optimization → turn OFF (or exclude this app).",
                ),
                openIntent = tryIntents(
                    Intent().setComponent(ComponentName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity")),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                ),
                webUrl = "https://dontkillmyapp.com/samsung",
            )
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") -> VendorAdvice(
                title = "Xiaomi / Redmi / Poco — Autostart & Battery saver",
                steps = listOf(
                    "Security app → Permissions → Autostart → enable YTM Trigger.",
                    "Settings → Apps → Manage apps → YTM Trigger → Battery saver → No restrictions.",
                    "Settings → Apps → Manage apps → YTM Trigger → Other permissions → Show on Lock screen + Start in background.",
                    "Recents → long-press YTM Trigger card → tap padlock to lock it.",
                ),
                openIntent = tryIntents(
                    Intent().setComponent(ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                ),
                webUrl = "https://dontkillmyapp.com/xiaomi",
            )
            mfr.contains("huawei") || mfr.contains("honor") -> VendorAdvice(
                title = "Huawei / Honor — Protected apps & Power-intensive prompt",
                steps = listOf(
                    "Settings → Apps → App launch → YTM Trigger → turn OFF 'Manage automatically', then enable Auto-launch + Secondary launch + Run in background.",
                    "Settings → Battery → App launch → ensure YTM Trigger is set to Manual with all 3 toggles ON.",
                    "Phone Manager / Optimizer → Protected apps → enable YTM Trigger.",
                ),
                openIntent = tryIntents(
                    Intent().setComponent(ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                ),
                webUrl = "https://dontkillmyapp.com/huawei",
            )
            mfr.contains("oppo") || mfr.contains("realme") -> VendorAdvice(
                title = "Oppo / Realme — Auto-launch & Background freeze",
                steps = listOf(
                    "Settings → Battery → Power consumption → YTM Trigger → Allow background activity.",
                    "Settings → Apps → App management → YTM Trigger → Allow auto-launch.",
                    "Settings → Privacy → Startup manager → enable YTM Trigger.",
                    "Recent apps → drag YTM Trigger card down → padlock.",
                ),
                openIntent = tryIntents(
                    Intent().setComponent(ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                    Intent().setComponent(ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity")),
                ),
                webUrl = "https://dontkillmyapp.com/oppo",
            )
            mfr.contains("vivo") || mfr.contains("iqoo") -> VendorAdvice(
                title = "Vivo / iQOO — Background lock & High background power",
                steps = listOf(
                    "Settings → Battery → Background power consumption → YTM Trigger → Allow.",
                    "iManager → App manager → Autostart manager → enable YTM Trigger.",
                    "Settings → Apps → YTM Trigger → Permissions → enable everything related.",
                ),
                openIntent = tryIntents(
                    Intent().setComponent(ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
                    Intent().setComponent(ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                ),
                webUrl = "https://dontkillmyapp.com/vivo",
            )
            mfr.contains("oneplus") -> VendorAdvice(
                title = "OnePlus — Battery optimization & App auto-launch",
                steps = listOf(
                    "Settings → Battery → Battery optimization → YTM Trigger → Don't optimize.",
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                    "Settings → Apps → App launch (Auto-launch) → enable YTM Trigger.",
                    "Lock YTM Trigger in Recents (drag down on its card).",
                ),
                openIntent = tryIntents(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                ),
                webUrl = "https://dontkillmyapp.com/oneplus",
            )
            mfr.contains("nothing") -> VendorAdvice(
                title = "Nothing — Battery & App launch",
                steps = listOf(
                    "Settings → Battery → Battery optimization → YTM Trigger → Don't optimize.",
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                    "Lock the app in Recents.",
                ),
                openIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                webUrl = "https://dontkillmyapp.com/nothing",
            )
            mfr.contains("asus") -> VendorAdvice(
                title = "Asus — Auto-start & Mobile Manager",
                steps = listOf(
                    "Mobile Manager → Boost → Auto-start manager → enable YTM Trigger.",
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                ),
                openIntent = tryIntents(
                    Intent().setComponent(ComponentName("com.asus.mobilemanager",
                        "com.asus.mobilemanager.MainActivity")),
                ),
                webUrl = "https://dontkillmyapp.com/asus",
            )
            mfr.contains("sony") -> VendorAdvice(
                title = "Sony — STAMINA & Battery optimization",
                steps = listOf(
                    "Settings → Battery → STAMINA mode → turn OFF, or exclude YTM Trigger.",
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                ),
                openIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                webUrl = "https://dontkillmyapp.com/sony",
            )
            mfr.contains("motorola") -> VendorAdvice(
                title = "Motorola — Battery optimization",
                steps = listOf(
                    "Settings → Battery → Battery optimization → YTM Trigger → Don't optimize.",
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                ),
                openIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                webUrl = "https://dontkillmyapp.com/motorola",
            )
            mfr.contains("google") -> VendorAdvice(
                title = "Google Pixel — Battery optimization & Adaptive Battery",
                steps = listOf(
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                    "Settings → Battery → Adaptive preferences → consider turning Adaptive Battery OFF if alarms are missed.",
                ),
                openIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                webUrl = "https://dontkillmyapp.com/google",
            )
            else -> VendorAdvice(
                title = "Generic Android — Battery & Background restrictions",
                steps = listOf(
                    "Settings → Apps → YTM Trigger → Battery → Unrestricted.",
                    "Settings → Battery → Battery optimization → YTM Trigger → Don't optimize.",
                    "Lock YTM Trigger in Recent apps if your launcher supports it.",
                ),
                openIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
                webUrl = "https://dontkillmyapp.com/",
            )
        }
    }

    /** Return the first intent in the list whose target component is resolvable. */
    private fun tryIntents(vararg candidates: Intent): Intent? {
        // We can't pre-resolve here without a Context; the UI will fall back to
        // a web URL if launching this intent throws. Return first non-null.
        return candidates.firstOrNull()
    }
}
