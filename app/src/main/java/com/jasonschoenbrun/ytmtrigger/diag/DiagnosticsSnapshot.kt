package com.jasonschoenbrun.ytmtrigger.diag

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
import com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.MediaSessionListenerService
import com.jasonschoenbrun.ytmtrigger.playback.MediaSessionProbe
import com.jasonschoenbrun.ytmtrigger.playback.NotifListenerEnforcer

/**
 * Captures a one-shot snapshot of every system signal we care about when
 * playback fires (or when the self-test runs). Each call writes a single
 * INFO log per signal, prefixed with the caller's [origin] tag, so logs can
 * be filtered later.
 *
 * Implements D-fix-1 through D-fix-12 from the v0.2.0 plan.
 */
object DiagnosticsSnapshot {

    /**
     * @param origin one of "PlaybackSvc" or "SelfTest" — appears in every log
     *               line as the source tag so we can disambiguate later.
     */
    fun capture(context: Context, origin: String) {
        try { logPlayStorePresence(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: ytmPkg err", t = t) }
        try { logPowerState(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: power err", t = t) }
        try { logAppStandby(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: standby err", t = t) }
        try { logBatteryWhitelist(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: whitelist err", t = t) }
        try { logBattery(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: battery err", t = t) }
        try { logNetwork(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: network err", t = t) }
        try { logAudioRouting(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: audio err", t = t) }
        try { logActivePlayers(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: activePlayers err", t = t) }
        try { logA11yEnabled(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: a11y err", t = t) }
        try { logNotifAccess(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: notifAccess err", t = t) }
        try { logFullScreenIntent(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: fullScreenIntent err", t = t) }
        try { logResolveActivity(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: resolveActivity err", t = t) }
        try { logForegroundApp(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: foreground err", t = t) }
        try { logMediaSession(context, origin) } catch (t: Throwable) { Logger.w(origin, "Diag: mediaSession err", t = t) }
    }

    /**
     * Capture a structured snapshot of every system signal we care about and
     * return it as a [DiagnosticsSnapshotData] instead of writing per-field
     * log lines. Used by [com.jasonschoenbrun.ytmtrigger.selftest
     * .SelfTestRunner] to attach diagnostics to each
     * [com.jasonschoenbrun.ytmtrigger.diag.SelfTestRunRecord] / per-attempt
     * record. Each field is wrapped in try/catch so a single broken probe
     * never masks the rest of the snapshot.
     */
    fun captureData(context: Context, origin: String): DiagnosticsSnapshotData {
        return DiagnosticsSnapshotData(
            capturedAtMs = System.currentTimeMillis(),
            origin = origin,
            ytmPackage = runCatching { dataYtmPackage(context) }.getOrNull(),
            power = runCatching { dataPower(context) }.getOrNull(),
            standbyBucket = runCatching { dataStandbyBucket(context) }.getOrNull(),
            batteryWhitelist = runCatching {
                context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrNull(),
            network = runCatching { dataNetwork(context) }.getOrNull(),
            audio = runCatching { dataAudio(context) }.getOrNull(),
            a11y = runCatching { dataA11y(context) }.getOrNull(),
            notifListener = runCatching { dataNotifListener(context) }.getOrNull(),
            fullScreenIntentAllowed = runCatching {
                if (Build.VERSION.SDK_INT < 34) null
                else context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent()
            }.getOrNull(),
            resolveActivity = runCatching { dataResolveActivity(context) }.getOrNull(),
            foregroundApp = runCatching { dataForegroundApp(context) }.getOrNull(),
            mediaSession = runCatching { MediaSessionProbe.ytMusicStatus(context).toString() }.getOrNull(),
            batteryLevelPct = runCatching { dataBatteryLevel(context) }.getOrNull(),
            charging = runCatching { dataCharging(context) }.getOrNull(),
            activePlayers = runCatching { dataActivePlayers(context) }.getOrNull().orEmpty(),
        )
    }

    private fun logPlayStorePresence(context: Context, origin: String) {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(YT_MUSIC_PKG, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(YT_MUSIC_PKG, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            Logger.w(origin, "Diag: YT Music not installed")
            return
        }
        Logger.i(origin, "Diag: ytmPkg", mapOf(
            "versionName" to (info.versionName ?: ""),
            "versionCode" to info.longVersionCode.toString(),
            "lastUpdate" to info.lastUpdateTime.toString(),
            "enabled" to (pm.getApplicationEnabledSetting(YT_MUSIC_PKG)).toString(),
        ))
    }

    private fun logPowerState(context: Context, origin: String) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        Logger.i(origin, "Diag: power", mapOf(
            "isInteractive" to pm.isInteractive.toString(),
            "isDeviceIdleMode" to pm.isDeviceIdleMode.toString(),
            "isPowerSaveMode" to pm.isPowerSaveMode.toString(),
            "isIgnoringBatteryOptimizations" to pm.isIgnoringBatteryOptimizations(context.packageName).toString(),
        ))
    }

    private fun logAppStandby(context: Context, origin: String) {
        if (Build.VERSION.SDK_INT < 28) return
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val bucket = try { usm.appStandbyBucket } catch (t: Throwable) { -1 }
        Logger.i(origin, "Diag: standbyBucket", mapOf(
            "bucket" to bucket.toString(),
            "bucketName" to standbyBucketName(bucket),
        ))
    }

    private fun standbyBucketName(b: Int): String = when (b) {
        // STANDBY_BUCKET_EXEMPTED (5) is @hide, so it has no public constant.
        // It is what a battery-optimization-exempt app reports, which is our
        // expected steady state — without this the logs read "UNKNOWN(5)".
        5 -> "EXEMPTED"
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "UNKNOWN($b)"
    }

    private fun logBatteryWhitelist(context: Context, origin: String) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        // H-fix-1: explicit log of battery whitelist status.
        Logger.i(origin, "Diag: batteryWhitelist", mapOf(
            "isIgnoringBatteryOptimizations" to pm.isIgnoringBatteryOptimizations(context.packageName).toString(),
        ))
    }

    private fun logNetwork(context: Context, origin: String) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val net = cm.activeNetwork
        if (net == null) {
            Logger.w(origin, "Diag: net=none")
            return
        }
        val caps = cm.getNetworkCapabilities(net)
        Logger.i(origin, "Diag: network", mapOf(
            "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true).toString(),
            "internet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true).toString(),
            "wifi" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true).toString(),
            "cell" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true).toString(),
            "metered" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false).toString(),
        ))
    }

    private fun logAudioRouting(context: Context, origin: String) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val deviceTypes = devices.joinToString(",") { deviceTypeName(it) }
        Logger.i(origin, "Diag: audio", mapOf(
            "mode" to am.mode.toString(),
            "modeName" to audioModeName(am.mode),
            "isMusicActive" to am.isMusicActive.toString(),
            "isBluetoothA2dpOn" to am.isBluetoothA2dpOn.toString(),
            "isWiredHeadsetOn" to am.isWiredHeadsetOn.toString(),
            "streamMusicVol" to am.getStreamVolume(AudioManager.STREAM_MUSIC).toString(),
            "streamMusicMax" to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toString(),
            "outputDevices" to deviceTypes,
        ))
    }

    private fun audioModeName(m: Int): String = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "OTHER($m)"
    }

    private fun deviceTypeName(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "T${d.type}"
    }

    private fun logA11yEnabled(context: Context, origin: String) {
        // I-fix-1: log A11y enabled status at every fire so we can correlate
        // post-launch action failures with the service being killed.
        val enabledSetting = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Throwable) { "" }
        val expected = "${context.packageName}/${YtmAccessibilityService::class.java.name}"
        val a11yOn = enabledSetting.split(":").any { it.equals(expected, ignoreCase = true) }
        Logger.i(origin, "Diag: a11y", mapOf(
            "enabledInSettings" to a11yOn.toString(),
            "serviceBound" to YtmAccessibilityService.isRunning().toString(),
            "serviceResponsive" to a11yResponsive(context).toString(),
        ))
    }

    private fun logNotifAccess(context: Context, origin: String) {
        val enabled = try {
            val s = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            val expected = "${context.packageName}/${MediaSessionListenerService::class.java.name}"
            s.split(":").any { it.equals(expected, ignoreCase = true) }
        } catch (_: Throwable) { false }
        Logger.i(origin, "Diag: notifListener", mapOf(
            "enabledInSettings" to enabled.toString(),
            "serviceConnected" to MediaSessionListenerService.isListenerConnected().toString(),
        ))
    }

    private fun logFullScreenIntent(context: Context, origin: String) {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        Logger.i(origin, "Diag: fullScreenIntent", mapOf(
            "canUse" to nm.canUseFullScreenIntent().toString(),
        ))
    }

    private fun logResolveActivity(context: Context, origin: String) {
        val pm = context.packageManager
        // Sample URI just for resolution; PendingIntent contents not actually used.
        val sample = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/playlist?list=PLfM0_AHRDDfL3sUPLfHpQyHbGqv1bMcCD"))
            .setPackage(YT_MUSIC_PKG)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(sample, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.resolveActivity(sample, 0)
            }
        } catch (_: Throwable) { null }
        Logger.i(origin, "Diag: resolveActivity", mapOf(
            "resolved" to (resolved != null).toString(),
            "activity" to (resolved?.activityInfo?.name ?: ""),
        ))
    }

    private fun logForegroundApp(context: Context, origin: String) {
        val pkg = dataForegroundApp(context)
        Logger.i(origin, "Diag: foreground", mapOf("pkg" to (pkg ?: "unknown")))
    }

    private fun logMediaSession(context: Context, origin: String) {
        val status = MediaSessionProbe.ytMusicStatus(context)
        Logger.i(origin, "Diag: mediaSession", mapOf("status" to status.toString()))
    }

    private fun logBattery(context: Context, origin: String) {
        val pct = dataBatteryLevel(context)
        val charging = dataCharging(context)
        Logger.i(origin, "Diag: battery", mapOf(
            "levelPct" to (pct?.toString() ?: "unknown"),
            "charging" to (charging?.toString() ?: "unknown"),
        ))
    }

    private fun logActivePlayers(context: Context, origin: String) {
        val players = dataActivePlayers(context)
        if (players.isEmpty()) {
            Logger.d(origin, "Diag: activePlayers=none")
            return
        }
        Logger.i(origin, "Diag: activePlayers", mapOf(
            "count" to players.size.toString(),
            "list" to players.joinToString(";") { "u=${it.usage},c=${it.contentType}" },
        ))
    }

    // --- Structured-data variants used by [captureData] -----------------

    private fun dataYtmPackage(context: Context): PackageInfoData? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(YT_MUSIC_PKG, PackageManager.PackageInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.getPackageInfo(YT_MUSIC_PKG, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return PackageInfoData(
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            lastUpdateMs = info.lastUpdateTime,
            enabledSetting = pm.getApplicationEnabledSetting(YT_MUSIC_PKG),
        )
    }

    private fun dataPower(context: Context): PowerStateData? {
        val pm = context.getSystemService(PowerManager::class.java) ?: return null
        return PowerStateData(
            isInteractive = pm.isInteractive,
            isDeviceIdleMode = pm.isDeviceIdleMode,
            isPowerSaveMode = pm.isPowerSaveMode,
            isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    private fun dataStandbyBucket(context: Context): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val b = try { usm.appStandbyBucket } catch (_: Throwable) { return null }
        return standbyBucketName(b)
    }

    private fun dataNetwork(context: Context): NetworkData? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork
        if (net == null) {
            return NetworkData(
                hasActiveNetwork = false,
                validated = false, internet = false, wifi = false, cellular = false, metered = false,
            )
        }
        val caps = cm.getNetworkCapabilities(net)
        return NetworkData(
            hasActiveNetwork = true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
        )
    }

    private fun dataAudio(context: Context): AudioStateData? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { deviceTypeName(it) }
        return AudioStateData(
            mode = am.mode,
            modeName = audioModeName(am.mode),
            isMusicActive = am.isMusicActive,
            isBluetoothA2dpOn = am.isBluetoothA2dpOn,
            isWiredHeadsetOn = am.isWiredHeadsetOn,
            streamMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            streamMusicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            outputDevices = devices,
        )
    }

    /**
     * Is the accessibility service actually usable right now?
     *
     * Picks the probe by thread, because the two available signals fail in
     * opposite situations:
     *
     * - [YtmAccessibilityService.canReadActiveWindow] is accurate while the
     *   screen is on, but blocks on a binder call. On the main thread, while
     *   one of this app's own windows is foreground, that call is served by
     *   this same thread and would deadlock until it times out. It also
     *   cannot distinguish a dead service from a sleeping screen, since with
     *   the display off there is no active window to read at all - hence
     *   [A11yPermissionEnforcer.isUsable], which reports "not broken" rather
     *   than "healthy" in that case.
     * - [YtmAccessibilityService.isResponsive] never blocks, but infers health
     *   from having seen an event since connecting. On an idle phone — the
     *   normal state for a dedicated alarm device between triggers — a
     *   perfectly healthy service legitimately receives no events, so it
     *   reports false. That was observed twice in real run records
     *   (2026-08-16 01:32 and 2026-08-19 21:27): `serviceResponsive=false`
     *   while the very same run went on to complete all four accessibility
     *   steps and succeed.
     *
     * Diagnostics run on background threads, so they get the accurate probe;
     * the UI checklist gets the non-blocking one, where our own window being
     * foreground guarantees a recent event anyway.
     */
    private fun a11yResponsive(context: Context): Boolean =
        if (Looper.myLooper() == Looper.getMainLooper()) {
            YtmAccessibilityService.isResponsive()
        } else {
            A11yPermissionEnforcer.isUsable(context)
        }

    private fun dataA11y(context: Context): A11yStateData? {
        val enabledSetting = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Throwable) { "" }
        val expected = "${context.packageName}/${YtmAccessibilityService::class.java.name}"
        val a11yOn = enabledSetting.split(":").any { it.equals(expected, ignoreCase = true) }
        return A11yStateData(
            enabledInSettings = a11yOn,
            serviceBound = YtmAccessibilityService.isRunning(),
            serviceResponsive = a11yResponsive(context),
        )
    }

    private fun dataNotifListener(context: Context): NotifListenerData {
        // Ask the framework, not the `enabled_notification_listeners` secure
        // setting: since Android 8 that setting is a compatibility write-back
        // and can claim access we don't actually have. See NotifListenerEnforcer.
        return NotifListenerData(
            enabledInSettings = NotifListenerEnforcer.isEnabled(context),
            serviceConnected = MediaSessionListenerService.isListenerConnected(),
        )
    }

    private fun dataResolveActivity(context: Context): ResolveActivityData {
        val pm = context.packageManager
        val sample = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/playlist?list=PLfM0_AHRDDfL3sUPLfHpQyHbGqv1bMcCD"))
            .setPackage(YT_MUSIC_PKG)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= 33) pm.resolveActivity(sample, PackageManager.ResolveInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.resolveActivity(sample, 0)
        } catch (_: Throwable) { null }
        return ResolveActivityData(
            resolved = resolved != null,
            activity = resolved?.activityInfo?.name,
        )
    }

    /**
     * The current foreground package.
     *
     * The accessibility service already knows this and needs no extra
     * permission, so it is preferred. UsageStats is only a fallback: it
     * silently returns nothing without PACKAGE_USAGE_STATS, which is an
     * appop the app cannot grant itself, and that made this field empty in
     * real run records.
     */
    private fun dataForegroundApp(context: Context): String? {
        YtmAccessibilityService.currentForegroundPackage()?.let { return it }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = try { usm.queryEvents(now - 10_000, now) } catch (_: Throwable) { return null }
        var lastPkg: String? = null
        val ev = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = ev.packageName
            }
        }
        return lastPkg
    }

    private fun dataBatteryLevel(context: Context): Int? {
        // The BatteryManager.BATTERY_PROPERTY_CAPACITY query is fast and
        // does not require any permission. Prefer it over the sticky
        // ACTION_BATTERY_CHANGED intent for level-only reads.
        return try {
            val bm = context.getSystemService(BatteryManager::class.java) ?: return null
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct < 0 || pct > 100) null else pct
        } catch (_: Throwable) { null }
    }

    private fun dataCharging(context: Context): Boolean? {
        // ACTION_BATTERY_CHANGED is a sticky broadcast; registering a null
        // receiver returns the current value without subscribing.
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    private fun dataActivePlayers(context: Context): List<ActivePlayerData> {
        val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val cfgs: List<AudioPlaybackConfiguration> = try {
            am.activePlaybackConfigurations ?: emptyList()
        } catch (_: Throwable) { return emptyList() }
        return cfgs.map { cfg ->
            val attrs = try { cfg.audioAttributes } catch (_: Throwable) { null }
            ActivePlayerData(
                usage = attrs?.usage ?: -1,
                contentType = attrs?.contentType ?: -1,
            )
        }
    }

    private const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"
}
