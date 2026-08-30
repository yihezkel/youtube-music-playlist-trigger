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
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository.get(ctx) }
    val settings by repo.flow.collectAsStateWithLifecycle()

    val urls = remember(settings.defaultPlaylistUrls) {
        mutableStateListOf<String>().apply { addAll(settings.defaultPlaylistUrls) }
    }
    var volPctText by remember(settings.defaultVolumePercent) {
        mutableStateOf(settings.defaultVolumePercent?.toString().orEmpty())
    }
    var enableShuffle by remember(settings.defaultEnableShuffle) {
        mutableStateOf(settings.defaultEnableShuffle)
    }
    var skipFirst by remember(settings.defaultSkipFirstTrack) {
        mutableStateOf(settings.defaultSkipFirstTrack)
    }
    var skipAds by remember(settings.skipAds) {
        mutableStateOf(settings.skipAds)
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
    var latText by remember(settings.latitude) { mutableStateOf(settings.latitude.toString()) }
    var keepScreenOn by remember(settings.keepScreenOnWhilePlaying) {
        mutableStateOf(settings.keepScreenOnWhilePlaying)
    }
    var dimScreen by remember(settings.dimWhileKeepingScreenOn) {
        mutableStateOf(settings.dimWhileKeepingScreenOn)
    }
    // Re-read on every recomposition: the user may have just returned from the
    // system settings screen where they granted it.
    val overlayOk = ScreenAwake.canDrawOverlays(ctx)
    var lonText by remember(settings.longitude) { mutableStateOf(settings.longitude.toString()) }
    var startOffText by remember(settings.shabatStartOffsetMin) {
        mutableStateOf(settings.shabatStartOffsetMin.toString())
    }
    var endOffText by remember(settings.shabatEndOffsetMin) {
        mutableStateOf(settings.shabatEndOffsetMin.toString())
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Default settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                IconButton(onClick = {
                    repo.update {
                        it.copy(
                            defaultPlaylistUrls = urls.map { it.trim() }.filter { it.isNotBlank() },
                            defaultVolumePercent = volPctText.toIntOrNull()?.coerceIn(0, 100),
                            defaultEnableShuffle = enableShuffle,
                            defaultSkipFirstTrack = skipFirst,
                            selfTestEnabled = selfTestEnabled,
                            skipAds = skipAds,
                            israeliObservance = !useDiasporaDates,
                            keepScreenOnWhilePlaying = keepScreenOn,
                            dimWhileKeepingScreenOn = dimScreen,
                            latitude = latText.toDoubleOrNull()?.coerceIn(-90.0, 90.0) ?: it.latitude,
                            longitude = lonText.toDoubleOrNull()?.coerceIn(-180.0, 180.0) ?: it.longitude,
                            shabatStartOffsetMin = startOffText.toIntOrNull()?.coerceIn(0, 180)
                                ?: it.shabatStartOffsetMin,
                            shabatEndOffsetMin = endOffText.toIntOrNull()?.coerceIn(0, 180)
                                ?: it.shabatEndOffsetMin,
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
            MediaEntryListEditor(urls)
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
                    Text("Skip ads automatically")
                    Text(
                        "Presses YouTube Music's skip button as soon as a skippable ad allows it. " +
                            "Only matters for tracks you haven't uploaded yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = skipAds, onCheckedChange = { skipAds = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on while music plays")
                    Text(
                        "Free-tier YouTube Music pauses anything you didn't upload once the " +
                            "screen sleeps. This holds the screen only while playing, so the " +
                            "developer \"Stay awake\" option can be turned off." +
                            if (!overlayOk) "  Needs \"Display over other apps\"." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!overlayOk) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it })
            }
            if (keepScreenOn && !overlayOk) {
                FilledTonalButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant \"Display over other apps\"") }
            }
            if (keepScreenOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dim the screen while holding it on")
                        Text(
                            "Keeps it technically on but practically black, which is what " +
                                "makes an hour of playback safe for the panel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = dimScreen, onCheckedChange = { dimScreen = it })
                }
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

            // Sunset-based Shabat window. Shown live so the effect of a change
            // is visible without waiting for a Friday.
            Text("Shabat / Yom Tov times", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText, onValueChange = { latText = it },
                    label = { Text("Latitude") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = lonText, onValueChange = { lonText = it },
                    label = { Text("Longitude") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startOffText,
                    onValueChange = { startOffText = it.filter { c -> c.isDigit() } },
                    label = { Text("Start: min before sunset") },
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = endOffText,
                    onValueChange = { endOffText = it.filter { c -> c.isDigit() } },
                    label = { Text("End: min after sunset") },
                    modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            run {
                val preview = HebrewCalendarChecker.Config(
                    israeliObservance = !useDiasporaDates,
                    latitude = latText.toDoubleOrNull() ?: settings.latitude,
                    longitude = lonText.toDoubleOrNull() ?: settings.longitude,
                    startOffsetMin = startOffText.toIntOrNull() ?: settings.shabatStartOffsetMin,
                    endOffsetMin = endOffText.toIntOrNull() ?: settings.shabatEndOffsetMin,
                )
                val (from, to) = HebrewCalendarChecker.nextShabatWindow(LocalDate.now(), preview)
                val f = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.US)
                Text(
                    "Next Shabat: ${f.format(from)} → ${f.format(to)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Computed from real sunset at the coordinates above, so it tracks " +
                        "the seasons instead of assuming a fixed clock time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

// ---------------------------------------------------------------- health
