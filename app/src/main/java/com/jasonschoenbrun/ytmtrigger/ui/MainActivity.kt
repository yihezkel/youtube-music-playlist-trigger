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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jasonschoenbrun.ytmtrigger.BuildConfig
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
            MaterialTheme(colorScheme = AppDarkColors) {
                Surface(color = MaterialTheme.colorScheme.background) { AppNav() }
            }
        }
    }
}

private val BrandRed          = Color(0xFFFF1744)
private val BrandRedContainer = Color(0xFF8B0017)
private val Accent            = Color(0xFFB59CFF)
private val AccentContainer   = Color(0xFF4A3E80)
private val BgNeutral         = Color(0xFF101013)
private val SurfaceNeutral    = Color(0xFF15151A)
private val SurfaceElevated   = Color(0xFF1F1F26)
private val SurfaceMuted      = Color(0xFF2A2A33)
private val OnSurface         = Color(0xFFEBE6F0)
private val OnSurfaceMuted    = Color(0xFFB5B0BD)
private val OutlineMuted      = Color(0xFF3A3A45)

private val AppDarkColors = darkColorScheme(
    primary             = BrandRed,
    onPrimary           = Color.White,
    primaryContainer    = BrandRedContainer,
    onPrimaryContainer  = Color(0xFFFFD9DC),
    secondary           = Accent,
    onSecondary         = Color.Black,
    secondaryContainer  = AccentContainer,
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary            = Color(0xFF7FE3C4),
    onTertiary          = Color.Black,
    background          = BgNeutral,
    onBackground        = OnSurface,
    surface             = SurfaceNeutral,
    onSurface           = OnSurface,
    surfaceVariant      = SurfaceMuted,
    onSurfaceVariant    = OnSurfaceMuted,
    outline             = OutlineMuted,
    outlineVariant      = Color(0xFF2A2A33),
    error               = Color(0xFFFF6E7C),
    onError             = Color.Black,
    errorContainer      = Color(0xFF6B0018),
    onErrorContainer    = Color(0xFFFFD9DD),
)

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
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "YTM Trigger",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PermsCard(perms)
            SectionCard(
                title = "Trigger now",
                icon = Icons.Default.PlayArrow,
            ) {
                if (schedules.isEmpty()) {
                    Text(
                        "Add a schedule first to define which playlists to use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in schedules) {
                            Button(
                                onClick = {
                                    Logger.i("UI", "Manual trigger", mapOf("scheduleId" to s.id))
                                    PlaybackTriggerService.startManual(ctx, s.id)
                                    // B-fix-5: bow out of MainActivity so it can't sit on top
                                    // of YT Music after the launch intent fires. The trigger
                                    // service routes through KeyguardDismissActivity which
                                    // will become the top activity.
                                    (ctx as? android.app.Activity)?.finish()
                                },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Play '${s.name}'",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            SectionCard(
                title = "Remote control",
                icon = Icons.Default.Cloud,
            ) {
                val scope = rememberCoroutineScope()
                var remoteStatus by remember {
                    mutableStateOf(com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.statusText(ctx))
                }
                var busy by remember { mutableStateOf(false) }
                Text(
                    remoteStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.isInitialised(ctx)) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.isReady(ctx)) {
                            FilledTonalButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        com.jasonschoenbrun.ytmtrigger.remote.RemoteSync
                                            .syncOnce(ctx, reason = "manual")
                                        com.jasonschoenbrun.ytmtrigger.remote.RemoteSync
                                            .uploadLogs(ctx, days = 3)
                                        remoteStatus = com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.statusText(ctx)
                                        busy = false
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (busy) "Syncing…" else "Sync now") }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.signOut(ctx)
                                    remoteStatus = com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.statusText(ctx)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Sign out") }
                        } else {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        com.jasonschoenbrun.ytmtrigger.remote.RemoteAuth.signIn(ctx)
                                        com.jasonschoenbrun.ytmtrigger.remote.RemoteSync
                                            .syncOnce(ctx, reason = "post-sign-in")
                                        remoteStatus = com.jasonschoenbrun.ytmtrigger.remote.RemoteGate.statusText(ctx)
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Signing in…" else "Sign in with Google") }
                        }
                    }
                }
            }
            SectionCard(
                title = "Next scheduled triggers",
                icon = Icons.Default.Schedule,
            ) {
                val fmt = SimpleDateFormat("EEE MMM d, HH:mm", Locale.US)
                if (schedules.none { it.enabled }) {
                    Text(
                        "No enabled schedules.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (s in schedules.filter { it.enabled }) {
                            val next = AlarmScheduler.computeNextTriggerMs(s)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s.name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (next != null) fmt.format(Date(next)) else "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NavTile("Schedules", Icons.Default.Schedule, Modifier.weight(1f)) { onNav(Screen.Schedules) }
                NavTile("Logs", Icons.Default.List, Modifier.weight(1f)) { onNav(Screen.Logs) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NavTile("Self-test", Icons.Default.BugReport, Modifier.weight(1f)) { onNav(Screen.SelfTest) }
                NavTile("Settings", Icons.Default.Settings, Modifier.weight(1f)) { onNav(Screen.Settings) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = SurfaceElevated,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon, null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = modifier.height(108.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
    val time = "%02d:%02d".format(schedule.timeMinutes / 60, schedule.timeMinutes % 60)
    val nextMs = if (schedule.enabled) AlarmScheduler.computeNextTriggerMs(schedule) else null
    val nextLabel = nextMs?.let {
        val fmt = java.text.SimpleDateFormat("EEE MMM d, HH:mm", java.util.Locale.US)
        "Next: ${fmt.format(java.util.Date(it))}"
    } ?: if (schedule.enabled) "Next: —" else "Disabled"
    val titleColor = if (schedule.enabled) cs.onSurface else cs.onSurfaceVariant

    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (schedule.enabled) cs.primary else cs.outlineVariant)
            )
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            schedule.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = titleColor,
                        )
                        Text(
                            nextLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Text(
                        time,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Light,
                        color = titleColor,
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = schedule.enabled, onCheckedChange = onToggle)
                }
                DayOfWeekStrip(selected = schedule.daysOfWeek, onClick = null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.QueueMusic, null,
                        modifier = Modifier.size(16.dp),
                        tint = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${schedule.playlistUrls.size} playlist" +
                            if (schedule.playlistUrls.size == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    if (schedule.enableShuffle) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Default.Shuffle, null,
                            modifier = Modifier.size(16.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    if (schedule.skipFirstTrack) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.SkipNext, null,
                            modifier = Modifier.size(16.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    schedule.targetVolumePercent?.let {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Default.VolumeUp, null,
                            modifier = Modifier.size(16.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$it%",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
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
            val borderStroke = if (isSel) null else BorderStroke(1.dp, cs.outline)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .then(borderStroke?.let { Modifier.border(it, CircleShape) } ?: Modifier)
                    .then(
                        if (onClick != null) Modifier.clickable { onClick(d) } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
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
                IconButton(
                    onClick = {
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
                    },
                    // E-fix-2: scheduling needs at least one day; otherwise the
                    // alarm never fires.
                    enabled = days.isNotEmpty(),
                ) { Icon(Icons.Default.Check, null) }
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
            if (days.isEmpty()) {
                Text(
                    "Pick at least one day — save is disabled until then.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
        val a11ySvc = com.jasonschoenbrun.ytmtrigger.accessibility.YtmAccessibilityService
        val a11yEnf = com.jasonschoenbrun.ytmtrigger.accessibility.A11yPermissionEnforcer
        checks += SelfTestRow(
            label = "Accessibility service running",
            ok = a11ySvc.isResponsive(),
            details = if (a11ySvc.isResponsive()) {
                "Required to press Play, enable shuffle, skip first track, and dismiss Premium upsells."
            } else if (a11ySvc.isRunning()) {
                "The service is bound but unresponsive — it can't read the active window, so it won't receive events or press Play. Android reports it as enabled, which is why this needs its own check. Tap Restart service to force Android to re-bind it."
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
        }
    }
}

@Composable
private fun BackgroundSelfTestCard() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val s by repo.flow.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("EEE MMM d, HH:mm:ss", Locale.US)
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
                        Logger.i("UI", "Manual self-test requested")
                        com.jasonschoenbrun.ytmtrigger.selftest.SelfTestReceiver.fireManual(ctx)
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
    var selfTestEnabled by remember(settings.selfTestEnabled) {
        mutableStateOf(settings.selfTestEnabled)
    }
    // Storage uses `israeliObservance` (default = true = Israel). The UI
    // exposes the inverse — "Use Diaspora dates" — so the default-OFF state
    // reads naturally.
    var useDiasporaDates by remember(settings.israeliObservance) {
        mutableStateOf(!settings.israeliObservance)
    }
    var selfTestUrlText by remember(settings.selfTestPlaylistUrl) {
        mutableStateOf(settings.selfTestPlaylistUrl.orEmpty())
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
                            selfTestEnabled = selfTestEnabled,
                            israeliObservance = !useDiasporaDates,
                            selfTestPlaylistUrl = selfTestUrlText.trim().ifBlank { null },
                        )
                    }
                    // Apply self-test toggle immediately so the user doesn't
                    // have to wait for the next launch for it to take effect.
                    com.jasonschoenbrun.ytmtrigger.selftest.SelfTestScheduler
                        .ensureScheduled(ctx, selfTestEnabled)
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

            Divider(Modifier.padding(vertical = 8.dp))
            Text("Self-test", style = MaterialTheme.typography.titleSmall)
            Text(
                "Runs every 6 hours, silently. Plays an audible alert if YouTube Music " +
                    "playback can't be started. Skipped on Shabat and Yom Tov.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable self-test", Modifier.weight(1f))
                Switch(checked = selfTestEnabled, onCheckedChange = { selfTestEnabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Diaspora dates")
                    Text(
                        "Off (default): Israel single-day Yom Tov. " +
                            "On: two-day Diaspora observance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useDiasporaDates, onCheckedChange = { useDiasporaDates = it })
            }
            OutlinedTextField(
                value = selfTestUrlText,
                onValueChange = { selfTestUrlText = it },
                label = { Text("Self-test playlist URL (blank = first default)") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        "Used only for the silent self-test. The volume is muted to 0 " +
                            "during the test and music is paused as soon as it starts."
                    )
                },
            )
        }
    }
}
