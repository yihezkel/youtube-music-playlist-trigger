package com.jasonschoenbrun.ytmtrigger.ui

// Split out of MainActivity.kt, which had grown to 2,344 lines holding six
// screens. Same package, so this is a move: no call site changed. The import
// list is the one MainActivity carried; unused entries are harmless.

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.jasonschoenbrun.ytmtrigger.health.Check
import com.jasonschoenbrun.ytmtrigger.health.Health
import com.jasonschoenbrun.ytmtrigger.health.HealthChecks
import com.jasonschoenbrun.ytmtrigger.health.HealthReport
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jasonschoenbrun.ytmtrigger.BuildConfig
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.alarm.ScheduleTimes
import com.jasonschoenbrun.ytmtrigger.calendar.HebrewCalendarChecker
import com.jasonschoenbrun.ytmtrigger.calendar.calendarConfig
import com.jasonschoenbrun.ytmtrigger.data.MediaEntries
import com.jasonschoenbrun.ytmtrigger.data.MediaKind
import com.jasonschoenbrun.ytmtrigger.data.PlaylistUrl
import com.jasonschoenbrun.ytmtrigger.data.PodcastEpisodeMode
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleChain
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.data.TimeAnchor
import com.jasonschoenbrun.ytmtrigger.diag.FailureLog
import com.jasonschoenbrun.ytmtrigger.log.LogLevel
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackTriggerService
import com.jasonschoenbrun.ytmtrigger.screen.ScreenAwake
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val checks = remember { mutableStateListOf<SelfTestRow>() }
    val bgStatus = remember { mutableStateOf<BackgroundRestrictionStatus?>(null) }

    fun runChecks() {
        checks.clear()
        checks += SelfTestRow(
            label = "YouTube Music installed",
            ok = runCatching {
                ctx.packageManager.getPackageInfo(PlaybackTriggerService.YT_MUSIC_PKG, 0); true
            }.getOrDefault(false),
            details = "Package ${PlaybackTriggerService.YT_MUSIC_PKG}",
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        checks += SelfTestRow(
            label = "Exact alarms permitted",
            ok = am?.canScheduleExactAlarms() == true,
            details = "Required for scheduled triggers to fire on time.",
            actionLabel = if (am?.canScheduleExactAlarms() != true) "Open settings" else null,
            action = {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        checks += SelfTestRow(
            label = "Notifications permitted",
            ok = notifOk,
            details = "The app must show an ongoing notification while triggering playback (foreground service requirement).",
            actionLabel = if (!notifOk) "Open app info" else null,
            action = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
        val a11ySvc = com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
        val a11yEnf = com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
        checks += SelfTestRow(
            label = "Accessibility service verified",
            ok = a11ySvc.isResponsive(),
            details = if (a11ySvc.isResponsive()) {
                "Required to press Play, enable shuffle, skip first track, and dismiss Premium upsells."
            } else if (a11ySvc.isRunning()) {
                "Bound, but not verified since it last connected. The service is scoped to YouTube Music, so it receives no events at all until YouTube Music next opens — which is normal for a while after a restart or an update. If YouTube Music has opened since and this still says so, the binding is dead and needs restarting. The next trigger or self-test settles it either way."
            } else {
                "Required to press Play, enable shuffle, skip first track, and dismiss Premium upsells. The service is not running."
            },
            actionLabel = if (a11ySvc.isResponsive()) {
                null
            } else if (a11ySvc.isRunning()) {
                if (a11yEnf.hasWriteSecureSettings(ctx)) "Restart service" else "Open Accessibility"
            } else {
                if (a11yEnf.hasWriteSecureSettings(ctx)) "Auto-enable" else "Open Accessibility"
            },
            action = {
                if (a11yEnf.hasWriteSecureSettings(ctx)) {
                    if (a11ySvc.isRunning()) a11yEnf.restart(ctx) else a11yEnf.ensureEnabled(ctx)
                } else {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
        // Auto-heal diag row: shows whether WRITE_SECURE_SETTINGS has been
        // granted via adb, so the app can re-enable its own Accessibility
        // service if Android disables it (after reboot / OS update / etc.).
        val hasSecureWrite = com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer.hasWriteSecureSettings(ctx)
        val grantCmd = com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer.adbGrantCommand(ctx)
        checks += SelfTestRow(
            label = "Accessibility auto-heal",
            ok = hasSecureWrite,
            details = if (hasSecureWrite) {
                "WRITE_SECURE_SETTINGS granted — the app will automatically re-enable the Accessibility service if Android ever disables it."
            } else {
                "Not granted. Without this, you'll need to re-enable the Accessibility service manually if Android ever disables it. To grant once (persists across reboots and updates), run this on a computer with adb:\n\n$grantCmd"
            },
            actionLabel = if (!hasSecureWrite) "Copy adb command" else null,
            action = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb grant", grantCmd))
                android.widget.Toast.makeText(ctx, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            },
        )
        // MediaSession probe row. Without notification-listener access,
        // getActiveSessions() throws and self-test run records log every
        // mediaSession sample as "Unavailable", leaving only
        // AudioManager.isMusicActive (true for ANY audio, not just YT Music).
        val notifEnf = com.jasonschoenbrun.ytmtrigger.playback.NotifListenerEnforcer
        val listenerOk = notifEnf.isEnabled(ctx)
        val allowCmd = notifEnf.adbAllowCommand(ctx)
        checks += SelfTestRow(
            label = "MediaSession probe (notification access)",
            ok = listenerOk,
            details = if (listenerOk) {
                "Granted — playback is verified directly from YouTube Music's media session."
            } else {
                "Not granted. Playback detection falls back to AudioManager, which reports ANY audio as playing, and self-test records can't show YouTube Music's real playback state. Unlike Accessibility, this cannot be self-healed — grant it once from a computer with adb:\n\n$allowCmd\n\nOr turn on notification access for YTM Trigger manually."
            },
            actionLabel = if (!listenerOk) "Copy adb command" else null,
            action = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb allow_listener", allowCmd))
                android.widget.Toast.makeText(ctx, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            },
        )

        // Background-restriction (vendor-specific) check
        val bg = BackgroundRestrictionChecker.check(ctx)
        bgStatus.value = bg
        checks += SelfTestRow(
            label = "Battery optimization disabled",
            ok = bg.osIgnoringBatteryOptimization,
            details = "If on, Android may delay or skip alarms in Doze mode.",
            actionLabel = if (!bg.osIgnoringBatteryOptimization) "Allow" else null,
            action = {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
        checks += SelfTestRow(
            label = "Not background-restricted (OS)",
            ok = !bg.osBackgroundRestricted,
            details = if (bg.osBackgroundRestricted)
                "OS reports this app is background-restricted; alarms and FGS will be killed."
            else "OS reports no background restriction.",
            actionLabel = if (bg.osBackgroundRestricted) "Open app info" else null,
            action = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        Logger.i("SelfTest", "Results", checks.associate { it.label to it.ok.toString() } +
            mapOf(
                "manufacturer" to bg.manufacturer,
                "osIgnoringBatOpt" to bg.osIgnoringBatteryOptimization.toString(),
                "osBackgroundRestricted" to bg.osBackgroundRestricted.toString(),
            )
        )
    }

    LaunchedEffect(Unit) { runChecks() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Health & self-test") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                IconButton(onClick = { runChecks() }) { Icon(Icons.Default.Refresh, null) }
            },
        )
    }) { inner ->
        val ctx2 = LocalContext.current
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); tick++ } }
        val report = remember(tick) { HealthChecks.run(ctx2) }
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The health report first: it answers "is anything wrong" in one
            // line, which is the question someone opening this screen has.
            // Worst first, so a problem never needs scrolling to.
            HealthHeader(report)
            val order = mapOf(Health.Broken to 0, Health.Degraded to 1, Health.Ok to 2)
            for (c in report.checks.sortedBy { order[it.health] }) HealthRow(c)
            Text(
                "Green means everything the app can do, it can do. Orange means something " +
                    "is wrong and the app already handles it. Red means something is wrong " +
                    "and nothing covers it, so a block will be missed or silent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "The checks above say whether something is wrong. These say how to put it " +
                    "right, and can act on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (row in checks) SelfTestRowView(row)

            Spacer(Modifier.height(8.dp))

            // Background self-test history + manual trigger.
            BackgroundSelfTestCard()

            // Vendor-specific advice card. Always shown — even if OS-level
            // signals are green — because vendors layer their own restriction
            // systems that don't expose APIs.
            bgStatus.value?.vendorAdvice?.let { advice ->
                VendorAdviceCard(
                    advice = advice,
                    osLevelOk = bgStatus.value?.osLevelOk == true,
                )
            }

            RecentFailuresCard()
        }
    }
}

/**
 * Last week's failures — self-tests and real triggers alike — with a per-day
 * bar chart and one plain sentence each.
 *
 * The data existed before but not in an answerable form: self-test outcomes
 * were buried in forensic records, trigger failures only in notification text,
 * and settings kept just the single latest failure.
 */
@Composable
private fun RecentFailuresCard() {
    val ctx = LocalContext.current
    // Recomputed whenever this screen is composed; failures are rare enough
    // that polling would be pointless.
    val entries = remember { FailureLog.recent(ctx, days = 7) }
    val counts = remember(entries) { FailureLog.dailyCounts(entries, days = 7) }
    val cs = MaterialTheme.colorScheme

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Failures in the last week",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (entries.isEmpty()) {
                Text(
                    "No failures in the last week!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.primary,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
                val dayFmt = SimpleDateFormat("EEE", Locale.US)
                val dayMs = 24L * 60 * 60 * 1000
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    counts.forEachIndexed { i, c ->
                        val label = dayFmt.format(Date(System.currentTimeMillis() - (6 - i) * dayMs))
                        // Every column is the same height, with the bar bottom
                        // aligned inside a fixed box. Letting the column grow
                        // with the bar pushed the day labels out of view.
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (c > 0) c.toString() else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                            Box(
                                Modifier.fillMaxWidth().height(44.dp),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        // Always leave a sliver so empty days
                                        // read as "nothing here", not "no chart".
                                        .height((4 + (40 * c / max)).dp)
                                        .background(
                                            if (c > 0) cs.error else cs.outlineVariant,
                                            RoundedCornerShape(4.dp),
                                        )
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                val stampFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in entries.take(12)) {
                        Column {
                            Text(
                                "${stampFmt.format(Date(e.atMs))} · ${e.kind}",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                            Text(e.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (entries.size > 12) {
                        Text(
                            "…and ${entries.size - 12} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundSelfTestCard() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val s by repo.flow.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("EEE MMM d, HH:mm:ss", Locale.US)
    // Non-null while the user is confirming a self-test during Shabat / Yom Tov.
    // The self-test starts real playback (muted), so it gets the same
    // confirmation as a manual Play now rather than running silently on chag.
    var confirmSelfTest by remember { mutableStateOf<String?>(null) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Background self-test", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs every 6 hours. Verifies end-to-end that YouTube Music can be " +
                    "launched and starts playing. Volume is muted during the test.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (s.selfTestEnabled) "Currently enabled." else "Currently disabled — toggle in Default settings.",
                style = MaterialTheme.typography.bodySmall,
                color = if (s.selfTestEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )

            // Inlined three rows: local @Composable funs are unsupported on
            // some Compose-Kotlin versions, so just emit them directly.
            for ((label, ms, extra) in listOf(
                Triple("Last success", s.lastSelfTestSuccessMs, s.lastSelfTestSuccessStrategy),
                Triple("Last failure", s.lastSelfTestFailureMs, s.lastSelfTestFailureReason),
                Triple("Last skip",    s.lastSelfTestSkipMs,    s.lastSelfTestSkipReason),
            )) {
                if (ms <= 0) {
                    Text("$label: never", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "$label: ${fmt.format(Date(ms))}" + (extra?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = {
                        val cal = HebrewCalendarChecker.check(
                            LocalDateTime.now(),
                            s.calendarConfig(),
                        )
                        if (cal.skip) {
                            confirmSelfTest = cal.reason ?: "Shabat/Yom Tov"
                        } else {
                            Logger.i("UI", "Manual self-test requested")
                            com.jasonschoenbrun.ytmtrigger.selftest.SelfTestReceiver.fireManual(ctx)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Run now")
                }
                FilledTonalButton(
                    onClick = {
                        Logger.i("UI", "Stop self-test alert")
                        com.jasonschoenbrun.ytmtrigger.selftest.SelfTestAlertService.stop(ctx)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop alert")
                }
            }
            // Second row: export the structured per-run records. Tap to share
            // the 20 most-recent SelfTestRunRecord entries as pretty JSON.
            FilledTonalButton(
                onClick = {
                    Logger.i("UI", "Export self-test runs requested")
                    val f = com.jasonschoenbrun.ytmtrigger.diag.SelfTestRunStore
                        .exportRecentAsJson(ctx, max = 20, appVersion = BuildConfig.VERSION_NAME)
                    if (f == null) {
                        android.widget.Toast.makeText(
                            ctx, "No self-test runs recorded yet", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        return@FilledTonalButton
                    }
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(
                        Intent.createChooser(send, "Share self-test runs")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Export last 20 runs (JSON)")
            }
        }
    }

    confirmSelfTest?.let { reason ->
        AlertDialog(
            onDismissRequest = { confirmSelfTest = null },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("It's $reason") },
            text = {
                Text(
                    "The self-test starts YouTube Music playing (muted) to prove the " +
                        "setup still works. Run it anyway?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSelfTest = null
                    Logger.i("UI", "Manual self-test requested", mapOf("calendarOverride" to "true"))
                    com.jasonschoenbrun.ytmtrigger.selftest.SelfTestReceiver.fireManual(ctx)
                }) { Text("Run anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSelfTest = null }) { Text("Cancel") }
            },
        )
    }
}

private data class SelfTestRow(
    val label: String,
    val ok: Boolean,
    val details: String? = null,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

@Composable
private fun SelfTestRowView(row: SelfTestRow) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (row.ok) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    tint = if (row.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(row.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (!row.ok && row.actionLabel != null && row.action != null) {
                    TextButton(onClick = { row.action.invoke() }) { Text(row.actionLabel) }
                }
            }
            row.details?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VendorAdviceCard(advice: VendorAdvice, osLevelOk: Boolean) {
    val ctx = LocalContext.current
    val container = if (osLevelOk) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (osLevelOk) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onErrorContainer
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = container, contentColor = onContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text(advice.title, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (osLevelOk)
                    "OS-level checks passed, but most Android vendors layer their own background-restriction systems on top. These cannot be detected from inside the app. Please verify the steps below to make sure schedules will fire reliably:"
                else
                    "OS-level checks failed. Apply the steps below; this device's vendor likely also has its own restrictions you'll want to disable:",
                style = MaterialTheme.typography.bodySmall,
            )
            for (step in advice.steps) {
                Row {
                    Text("• ", fontWeight = FontWeight.Bold)
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                advice.openIntent?.let { intent ->
                    Button(
                        onClick = {
                            try {
                                ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                Logger.i("SelfTest", "Opened vendor settings intent")
                            } catch (t: Throwable) {
                                Logger.w("SelfTest", "Vendor intent unresolvable; falling back to web", t = t)
                                advice.webUrl?.let { url ->
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        },
                    ) { Text("Open settings") }
                }
                advice.webUrl?.let { url ->
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    ) { Text("More info") }
                }
            }
        }
    }
}

@Composable
internal fun PermsCard(perms: PermissionState) {
    if (perms.allGranted) return
    val ctx = LocalContext.current
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup needed", fontWeight = FontWeight.Bold)
            if (!perms.exactAlarms) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Allow exact alarms") }
            }
            if (!perms.accessibility) {
                val enf = com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
                if (enf.hasWriteSecureSettings(ctx)) {
                    Button(onClick = { enf.ensureEnabled(ctx) }) {
                        Text("Auto-enable Accessibility service")
                    }
                } else {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Enable Accessibility service (YTM Trigger Helper)") }
                }
            }
            if (!perms.batteryOptOff) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Disable battery optimization") }
            }
        }
    }
}

internal data class PermissionState(
    val exactAlarms: Boolean,
    val accessibility: Boolean,
    val batteryOptOff: Boolean,
) {
    val allGranted get() = exactAlarms && accessibility && batteryOptOff
}

@Composable
internal fun rememberPermissionState(): PermissionState {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { tick++; kotlinx.coroutines.delay(2000) } }
    return remember(tick) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        PermissionState(
            exactAlarms = am?.canScheduleExactAlarms() == true,
            accessibility = com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService.isRunning(),
            batteryOptOff = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
        )
    }
}

@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
