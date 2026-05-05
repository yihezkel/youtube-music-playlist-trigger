package com.jasonschoenbrun.ytmtrigger.ui

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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.data.PlaylistUrl
import com.jasonschoenbrun.ytmtrigger.data.Schedule
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.data.SettingsRepository
import com.jasonschoenbrun.ytmtrigger.log.LogLevel
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackTriggerService
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Logger.i("UI", "Notification permission result", mapOf("granted" to granted.toString()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("UI", "MainActivity onCreate")
        // Request notif permission proactively
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { AppNav() }
            }
        }
    }
}

@Composable
fun AppNav() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        Screen.Home -> HomeScreen(onNav = { screen = it })
        Screen.Schedules -> SchedulesScreen(onNav = { screen = it })
        is Screen.Edit -> EditScheduleScreen(scheduleId = s.id, onDone = { screen = Screen.Schedules })
        Screen.Logs -> LogsScreen(onBack = { screen = Screen.Home })
        Screen.SelfTest -> SelfTestScreen(onBack = { screen = Screen.Home })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
    }
}

sealed class Screen {
    data object Home : Screen()
    data object Schedules : Screen()
    data class Edit(val id: String?) : Screen()
    data object Logs : Screen()
    data object SelfTest : Screen()
    data object Settings : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { ScheduleRepository.get(ctx) }
    val schedules by repo.flow.collectAsStateWithLifecycle()
    val perms = rememberPermissionState()
    Scaffold(topBar = { TopAppBar(title = { Text("YTM Trigger") }) }) { inner ->
        Column(
            Modifier.padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PermsCard(perms)
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trigger now", style = MaterialTheme.typography.titleMedium)
                    if (schedules.isEmpty()) {
                        Text("Add a schedule first to define which playlists to use.")
                    } else {
                        for (s in schedules) {
                            Button(
                                onClick = {
                                    Logger.i("UI", "Manual trigger", mapOf("scheduleId" to s.id))
                                    PlaybackTriggerService.startManual(ctx, s.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Play '${s.name}' now")
                            }
                        }
                    }
                }
            }
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Next scheduled triggers", style = MaterialTheme.typography.titleMedium)
                    val fmt = SimpleDateFormat("EEE MMM d, HH:mm", Locale.US)
                    if (schedules.none { it.enabled }) {
                        Text("No enabled schedules.")
                    } else {
                        for (s in schedules.filter { it.enabled }) {
                            val next = AlarmScheduler.computeNextTriggerMs(s)
                            Text("${s.name}: ${if (next != null) fmt.format(Date(next)) else "—"}")
                        }
                    }
                }
            }
            FilledTonalButton(onClick = { onNav(Screen.Schedules) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp)); Text("Schedules")
            }
            FilledTonalButton(onClick = { onNav(Screen.Logs) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.List, null); Spacer(Modifier.width(8.dp)); Text("Logs")
            }
            FilledTonalButton(onClick = { onNav(Screen.SelfTest) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(8.dp)); Text("Self-test")
            }
            FilledTonalButton(onClick = { onNav(Screen.Settings) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("Default settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(onNav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { ScheduleRepository.get(ctx) }
    val schedules by repo.flow.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Schedules") }, navigationIcon = {
            IconButton(onClick = { onNav(Screen.Home) }) { Icon(Icons.Default.ArrowBack, null) }
        }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNav(Screen.Edit(null)) }) { Icon(Icons.Default.Add, null) }
        },
    ) { inner ->
        if (schedules.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No schedules yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap + to create one", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(inner).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(schedules, key = { it.id }) { s ->
                    ScheduleCard(
                        schedule = s,
                        onClick = { onNav(Screen.Edit(s.id)) },
                        onToggle = { newEnabled ->
                            repo.upsert(s.copy(enabled = newEnabled))
                            AlarmScheduler.rescheduleAll(
                                ctx,
                                repo.all().map { x -> if (x.id == s.id) x.copy(enabled = newEnabled) else x },
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(schedule: Schedule, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val containerColor = if (schedule.enabled) cs.primaryContainer else cs.surfaceVariant
    val onContainer = if (schedule.enabled) cs.onPrimaryContainer else cs.onSurfaceVariant
    val time = "%02d:%02d".format(schedule.timeMinutes / 60, schedule.timeMinutes % 60)
    val nextMs = if (schedule.enabled) AlarmScheduler.computeNextTriggerMs(schedule) else null
    val nextLabel = nextMs?.let {
        val fmt = java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US)
        "Next: ${fmt.format(java.util.Date(it))}"
    } ?: if (schedule.enabled) "Next: —" else "Disabled"

    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor, contentColor = onContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(schedule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(nextLabel, style = MaterialTheme.typography.bodySmall)
                }
                Text(time, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(12.dp))
                Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            }
            DayOfWeekStrip(selected = schedule.daysOfWeek, onClick = null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueueMusic, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${schedule.playlistUrls.size} playlist" + if (schedule.playlistUrls.size == 1) "" else "s",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (schedule.enableShuffle) {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(16.dp))
                }
                if (schedule.skipFirstTrack) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(16.dp))
                }
                schedule.targetVolumePercent?.let {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$it%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val DAYS_DISPLAY = listOf(
    DayOfWeek.MONDAY to "M",
    DayOfWeek.TUESDAY to "T",
    DayOfWeek.WEDNESDAY to "W",
    DayOfWeek.THURSDAY to "T",
    DayOfWeek.FRIDAY to "F",
    DayOfWeek.SATURDAY to "S",
    DayOfWeek.SUNDAY to "S",
)

@Composable
private fun DayOfWeekStrip(
    selected: Set<Int>,
    onClick: ((DayOfWeek) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((d, letter) in DAYS_DISPLAY) {
            val isSel = d.value in selected
            val bg = if (isSel) cs.primary else Color.Transparent
            val fg = if (isSel) cs.onPrimary else cs.onSurfaceVariant
            val border = if (isSel) cs.primary else cs.outlineVariant
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .then(
                        if (onClick != null) Modifier.clickable { onClick(d) } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!isSel) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                            .background(Color.Transparent),
                    )
                }
                Text(
                    letter,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                // outline ring for unselected (drawn via stroked Surface workaround):
                if (!isSel) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                        drawCircle(
                            color = border,
                            radius = (size.minDimension / 2f) - 1f,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScheduleScreen(scheduleId: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { ScheduleRepository.get(ctx) }
    val settingsRepo = remember { SettingsRepository.get(ctx) }
    val initial = remember {
        scheduleId?.let { repo.byId(it) } ?: Schedule.fromDefaults(settingsRepo.current())
    }
    var name by remember { mutableStateOf(initial.name) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var timeMin by remember { mutableIntStateOf(initial.timeMinutes) }
    val days = remember { mutableStateListOf<Int>().apply { addAll(initial.daysOfWeek) } }
    val urls = remember { mutableStateListOf<String>().apply { addAll(initial.playlistUrls) } }
    var newUrl by remember { mutableStateOf(TextFieldValue("")) }
    var enableShuffle by remember { mutableStateOf(initial.enableShuffle) }
    var skipFirst by remember { mutableStateOf(initial.skipFirstTrack) }
    var volPctText by remember { mutableStateOf(initial.targetVolumePercent?.toString().orEmpty()) }
    var stopMinText by remember { mutableStateOf(initial.autoStopMinutes?.toString().orEmpty()) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (scheduleId == null) "New schedule" else "Edit schedule") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                if (scheduleId != null) {
                    IconButton(onClick = {
                        AlarmScheduler.cancel(ctx, scheduleId)
                        repo.delete(scheduleId)
                        onDone()
                    }) { Icon(Icons.Default.Delete, null) }
                }
                IconButton(onClick = {
                    val updated = initial.copy(
                        name = name.ifBlank { "Schedule" },
                        enabled = enabled,
                        timeMinutes = timeMin,
                        daysOfWeek = days.toSet(),
                        playlistUrls = urls.toList(),
                        enableShuffle = enableShuffle,
                        skipFirstTrack = skipFirst,
                        targetVolumePercent = volPctText.toIntOrNull()?.coerceIn(0, 100),
                        autoStopMinutes = stopMinText.toIntOrNull()?.coerceAtLeast(1),
                    )
                    repo.upsert(updated)
                    val all = repo.all().toMutableList()
                    val idx = all.indexOfFirst { it.id == updated.id }
                    if (idx >= 0) all[idx] = updated else all.add(updated)
                    AlarmScheduler.rescheduleAll(ctx, all)
                    onDone()
                }) { Icon(Icons.Default.Check, null) }
            },
        )
    }) { inner ->
        Column(
            Modifier.padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            // Time picker — tap card to open Material3 TimePickerDialog
            var showTimePicker by remember { mutableStateOf(false) }
            ElevatedCard(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Trigger time", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "%02d:%02d".format(timeMin / 60, timeMin % 60),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Light,
                        )
                    }
                    TextButton(onClick = { showTimePicker = true }) { Text("Change") }
                }
            }
            if (showTimePicker) {
                TimePickerDialog(
                    initialHour = timeMin / 60,
                    initialMinute = timeMin % 60,
                    onDismiss = { showTimePicker = false },
                    onConfirm = { h, m ->
                        timeMin = h * 60 + m
                        showTimePicker = false
                    },
                )
            }

            Text("Days of week", style = MaterialTheme.typography.titleSmall)
            DayOfWeekStrip(
                selected = days.toSet(),
                onClick = { d ->
                    if (days.contains(d.value)) days.remove(d.value) else days.add(d.value)
                },
            )
            Text("Playlists", style = MaterialTheme.typography.titleSmall)
            for ((i, u) in urls.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { urls.removeAt(i) }) { Icon(Icons.Default.Delete, null) }
                }
            }
            OutlinedTextField(
                value = newUrl, onValueChange = { newUrl = it },
                label = { Text("Add playlist URL (https://music.youtube.com/playlist?list=...)") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        val v = newUrl.text.trim()
                        if (PlaylistUrl.isValid(v)) { urls.add(v); newUrl = TextFieldValue("") }
                    }) { Icon(Icons.Default.Add, null) }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable shuffle after launch", Modifier.weight(1f))
                Switch(checked = enableShuffle, onCheckedChange = { enableShuffle = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Skip first track (random first song)", Modifier.weight(1f))
                Switch(checked = skipFirst, onCheckedChange = { skipFirst = it })
            }
            OutlinedTextField(
                value = volPctText, onValueChange = { volPctText = it.filter { c -> c.isDigit() } },
                label = { Text("Target media volume % (blank = don't change)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = stopMinText, onValueChange = { stopMinText = it.filter { c -> c.isDigit() } },
                label = { Text("Auto-stop after N minutes (blank = never)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val entries by Logger.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Logs (${entries.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        val text = entries.takeLast(500).joinToString("\n") { it.format() }
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("YTM Trigger logs", text))
                        android.widget.Toast.makeText(ctx, "Last ${entries.takeLast(500).size} log lines copied", android.widget.Toast.LENGTH_SHORT).show()
                        Logger.i("UI", "Logs copied to clipboard", mapOf("count" to entries.takeLast(500).size.toString()))
                    }) { Icon(Icons.Default.ContentCopy, null) }
                    IconButton(onClick = {
                        val f = Logger.exportLatestFile(ctx) ?: return@IconButton
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(Icons.Default.Share, null) }
                })
        }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (lvl in LogLevel.entries) {
                    FilterChip(
                        selected = minLevel == lvl,
                        onClick = { minLevel = lvl },
                        label = { Text(lvl.short) },
                    )
                }
            }
            OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            val visible = entries.asReversed().filter { e ->
                e.level.priority >= minLevel.priority &&
                    (filter.isBlank() || e.tag.contains(filter, true) || e.message.contains(filter, true))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible) { e ->
                    Text(
                        e.format(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Divider()
                }
            }
        }
    }
}

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
        checks += SelfTestRow(
            label = "Accessibility service running",
            ok = com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService.isRunning(),
            details = "Required to press Play, enable shuffle, skip first track, and dismiss Premium upsells.",
            actionLabel = if (!com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService.isRunning()) "Open Accessibility" else null,
            action = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            title = { Text("Self-test") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                IconButton(onClick = { runChecks() }) { Icon(Icons.Default.Refresh, null) }
            },
        )
    }) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in checks) SelfTestRowView(row)

            Spacer(Modifier.height(8.dp))

            // Vendor-specific advice card. Always shown — even if OS-level
            // signals are green — because vendors layer their own restriction
            // systems that don't expose APIs.
            bgStatus.value?.vendorAdvice?.let { advice ->
                VendorAdviceCard(
                    advice = advice,
                    osLevelOk = bgStatus.value?.osLevelOk == true,
                )
            }
        }
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
private fun PermsCard(perms: PermissionState) {
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
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Enable Accessibility service (YTM Trigger Helper)") }
            }
            if (!perms.batteryOptOff) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Disable battery optimization") }
            }
        }
    }
}

private data class PermissionState(
    val exactAlarms: Boolean,
    val accessibility: Boolean,
    val batteryOptOff: Boolean,
) {
    val allGranted get() = exactAlarms && accessibility && batteryOptOff
}

@Composable
private fun rememberPermissionState(): PermissionState {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trigger time") },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val settings by repo.flow.collectAsStateWithLifecycle()

    val urls = remember(settings.defaultPlaylistUrls) {
        mutableStateListOf<String>().apply { addAll(settings.defaultPlaylistUrls) }
    }
    var newUrl by remember { mutableStateOf(TextFieldValue("")) }
    var volPctText by remember(settings.defaultVolumePercent) {
        mutableStateOf(settings.defaultVolumePercent?.toString().orEmpty())
    }
    var enableShuffle by remember(settings.defaultEnableShuffle) {
        mutableStateOf(settings.defaultEnableShuffle)
    }
    var skipFirst by remember(settings.defaultSkipFirstTrack) {
        mutableStateOf(settings.defaultSkipFirstTrack)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Default settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                IconButton(onClick = {
                    repo.update {
                        it.copy(
                            defaultPlaylistUrls = urls.toList(),
                            defaultVolumePercent = volPctText.toIntOrNull()?.coerceIn(0, 100),
                            defaultEnableShuffle = enableShuffle,
                            defaultSkipFirstTrack = skipFirst,
                        )
                    }
                    onBack()
                }) { Icon(Icons.Default.Check, null) }
            },
        )
    }) { inner ->
        Column(
            Modifier.padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "These defaults are applied to new schedules you create. " +
                    "Existing schedules are not affected.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Default playlists", style = MaterialTheme.typography.titleSmall)
            for ((i, u) in urls.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { urls.removeAt(i) }) { Icon(Icons.Default.Delete, null) }
                }
            }
            OutlinedTextField(
                value = newUrl, onValueChange = { newUrl = it },
                label = { Text("Add playlist URL") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        val v = newUrl.text.trim()
                        if (PlaylistUrl.isValid(v)) { urls.add(v); newUrl = TextFieldValue("") }
                    }) { Icon(Icons.Default.Add, null) }
                }
            )
            OutlinedTextField(
                value = volPctText, onValueChange = { volPctText = it.filter { c -> c.isDigit() } },
                label = { Text("Default media volume % (blank = don't change)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Default: enable shuffle", Modifier.weight(1f))
                Switch(checked = enableShuffle, onCheckedChange = { enableShuffle = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Default: skip first track", Modifier.weight(1f))
                Switch(checked = skipFirst, onCheckedChange = { skipFirst = it })
            }
        }
    }
}
