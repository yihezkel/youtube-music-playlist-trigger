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
            PlaybackCard()
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
                            val next = AlarmScheduler.computeNextTriggerMs(ctx, s)
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
                NavTile("Settings", Icons.Default.Settings, Modifier.weight(1f)) { onNav(Screen.Settings) }
                Spacer(Modifier.weight(1f))
            }
            // The health tile is the way in to the self-test screen: the colour
            // is the reason to go there, and a separate "Self-test" tile beside
            // it would just be a second door to the same room.
            HealthTile { onNav(Screen.SelfTest) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlaybackCard() {
    val ctx = LocalContext.current
    // Polled rather than observed: the state can change from a stop alarm, a
    // queue advancing or the household pressing pause on the phone itself, none
    // of which report back here.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val snap = remember(tick) {
        com.jasonschoenbrun.ytmtrigger.playback.PlaybackPauser.snapshot(ctx)
    }
    val paused = snap.state == com.jasonschoenbrun.ytmtrigger.playback.PlaybackPauser.State.Paused
    val idle = snap.state == com.jasonschoenbrun.ytmtrigger.playback.PlaybackPauser.State.Idle
    SectionCard(
        title = "Playback",
        icon = if (paused) Icons.Default.PlayArrow else Icons.Default.MusicNote,
    ) {
        Text(
            when {
                paused -> "Paused" + (snap.what?.let { " — $it" } ?: "")
                idle -> "Nothing is playing."
                else -> "Playing" + (snap.what?.let { " — $it" } ?: "")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (paused) {
            Text(
                "The block is held where it is. It still ends at its stop time, " +
                    "and Shabat still stops it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            enabled = !idle || paused,
            onClick = {
                val p = com.jasonschoenbrun.ytmtrigger.playback.PlaybackPauser
                if (paused) p.resume(ctx, reason = "home screen") else p.pause(ctx, reason = "home screen")
                tick++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
            Spacer(Modifier.width(8.dp))
            Text(if (paused) "Resume" else "Pause")
        }
        // Stop is not pause. Pause holds the episode and its place in the
        // queue; stop ends the block, releasing the player and leaving only a
        // resume mark. The console has had this since the beginning; the app
        // never did.
        OutlinedButton(
            enabled = !idle,
            onClick = {
                com.jasonschoenbrun.ytmtrigger.playback.PlaybackStopper
                    .stop(ctx, reason = "home screen")
                tick++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("Stop")
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

internal fun colourFor(h: Health): Color = when (h) {
    Health.Ok -> HealthGreen
    Health.Degraded -> HealthOrange
    Health.Broken -> HealthRed
}

/**
 * The home-screen button, coloured by the worst thing it found.
 *
 * Re-checked while the screen is showing rather than once, because most of what
 * it looks at is a permission the user may be away changing right now, and a
 * stale green light is worse than no light.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthTile(onClick: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); tick++ } }
    val report = remember(tick) { HealthChecks.run(ctx) }
    val colour = colourFor(report.overall)
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(colour.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (report.overall) {
                        Health.Ok -> Icons.Default.CheckCircle
                        Health.Degraded -> Icons.Default.Warning
                        Health.Broken -> Icons.Default.Error
                    },
                    null,
                    modifier = Modifier.size(26.dp),
                    tint = colour,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Health check", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    report.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colour,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun HealthHeader(report: HealthReport) {
    val colour = colourFor(report.overall)
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colour.copy(alpha = 0.12f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (report.overall) {
                    Health.Ok -> Icons.Default.CheckCircle
                    Health.Degraded -> Icons.Default.Warning
                    Health.Broken -> Icons.Default.Error
                },
                null, tint = colour, modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(report.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${report.checks.size} checks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun HealthRow(c: Check) {
    val colour = colourFor(c.health)
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
                Spacer(Modifier.width(10.dp))
                Text(
                    c.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = colour)
            }
            if (c.consequence != null) {
                Text(
                    c.consequence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (c.fixAction != null) {
                Text(
                    "Fix: ${c.fixAction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
