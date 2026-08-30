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
    var enableShuffle by remember { mutableStateOf(initial.enableShuffle) }
    var skipFirst by remember { mutableStateOf(initial.skipFirstTrack) }
    var volPctText by remember { mutableStateOf(initial.targetVolumePercent?.toString().orEmpty()) }
    var stopMinText by remember { mutableStateOf(initial.autoStopMinutes?.toString().orEmpty()) }
    var stopTimeMin by remember { mutableStateOf(initial.stopTimeMinutes) }
    var continuous by remember { mutableStateOf(initial.continuousPlay) }
    var anchor by remember { mutableStateOf(initial.timeAnchor) }
    var anchorOffsetText by remember { mutableStateOf(initial.anchorOffsetMinutes.toString()) }
    var podcastMode by remember { mutableStateOf(initial.podcastEpisodeMode) }

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
                            playlistUrls = urls.map { it.trim() }.filter { it.isNotBlank() },
                            enableShuffle = enableShuffle,
                            skipFirstTrack = skipFirst,
                            targetVolumePercent = volPctText.toIntOrNull()?.coerceIn(0, 100),
                            autoStopMinutes = stopMinText.toIntOrNull()?.coerceAtLeast(1),
                            stopTimeMinutes = stopTimeMin,
                            timeAnchor = anchor,
                            // An unparseable or blank offset means "no shift"
                            // rather than a silent revert to the old value.
                            anchorOffsetMinutes = anchorOffsetText.trim()
                                .toIntOrNull()?.coerceIn(-720, 720) ?: 0,
                            podcastEpisodeMode = podcastMode,
                            continuousPlay = continuous,
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

            // Trigger time. A schedule is anchored either to the clock or to
            // something that moves with the calendar; only the relevant
            // control is shown so the two cannot be set to conflicting values.
            Text("Trigger time is measured from", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = anchor == TimeAnchor.FixedClock,
                    onClick = { anchor = TimeAnchor.FixedClock },
                    label = { Text("Clock") },
                )
                FilterChip(
                    selected = anchor == TimeAnchor.Sunset,
                    onClick = { anchor = TimeAnchor.Sunset },
                    label = { Text("Sunset") },
                )
                FilterChip(
                    selected = anchor == TimeAnchor.ShabatYomTovEnd,
                    onClick = { anchor = TimeAnchor.ShabatYomTovEnd },
                    label = { Text("Shabat ends") },
                )
            }

            var showTimePicker by remember { mutableStateOf(false) }
            if (anchor == TimeAnchor.FixedClock) {
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
            } else {
                OutlinedTextField(
                    value = anchorOffsetText,
                    onValueChange = { anchorOffsetText = it },
                    label = { Text("Minutes from anchor (negative = before)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (anchor == TimeAnchor.Sunset) {
                        "Fires relative to sunset, which shifts by over three " +
                            "hours across the year."
                    } else {
                        "Fires only on days a Shabat or Yom Tov window actually " +
                            "ends, so other ticked days are skipped."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            // Optional stop time. Empty by default; "Clear" puts it back to
            // empty so a schedule can go back to playing until stopped.
            var showStopPicker by remember { mutableStateOf(false) }
            ElevatedCard(
                onClick = { showStopPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.StopCircle, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Stop time (optional)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            stopTimeMin?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Light,
                            color = if (stopTimeMin == null) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        if (stopTimeMin != null && stopTimeMin!! <= timeMin) {
                            Text(
                                "Next day",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (stopTimeMin != null) {
                        TextButton(onClick = { stopTimeMin = null }) { Text("Clear") }
                    }
                    TextButton(onClick = { showStopPicker = true }) { Text("Change") }
                }
            }
            if (showStopPicker) {
                TimePickerDialog(
                    initialHour = (stopTimeMin ?: timeMin) / 60,
                    initialMinute = (stopTimeMin ?: timeMin) % 60,
                    onDismiss = { showStopPicker = false },
                    onConfirm = { h, m ->
                        stopTimeMin = h * 60 + m
                        showStopPicker = false
                    },
                )
            }

            // A chained block is never armed from the clock, so the trigger
            // time above is inert for it. Saying so beats leaving "Next: —"
            // to be puzzled over.
            val follows = remember(initial.startsAfter) {
                ScheduleChain.describeFollows(repo.all(), initial)
            }
            if (follows != null) {
                Text(
                    follows,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ScheduleChain.problems(repo.all()).any { it.scheduleId == initial.id }) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
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
            Text("Playlists and podcasts", style = MaterialTheme.typography.titleSmall)
            MediaEntryListEditor(urls)
            // Only meaningful when the schedule contains a podcast, so it is
            // hidden otherwise rather than adding a control that does nothing.
            if (urls.any {
                    val k = MediaEntries.parse(it).kind
                    k == MediaKind.PodcastFeed || k == MediaKind.SpotifyShow
                }
            ) {
                Text("Podcasts: which episode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = podcastMode == PodcastEpisodeMode.Random,
                        onClick = { podcastMode = PodcastEpisodeMode.Random },
                        label = { Text("Random") },
                    )
                    FilterChip(
                        selected = podcastMode == PodcastEpisodeMode.Latest,
                        onClick = { podcastMode = PodcastEpisodeMode.Latest },
                        label = { Text("Newest") },
                    )
                    FilterChip(
                        selected = podcastMode == PodcastEpisodeMode.Sequential,
                        onClick = { podcastMode = PodcastEpisodeMode.Sequential },
                        label = { Text("In order") },
                    )
                }
                Text(
                    "Random suits evergreen archives, Newest suits news and feeds that mix " +
                        "short and long formats, In order suits a show that tells one story " +
                        "across numbered parts.\n\nEach entry above can override this, and " +
                        "can set a shortest-episode length of its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Play continuously")
                    Text(
                        "Play the entries in order, starting the next the moment one " +
                            "finishes, until the stop time. Off means pick one and stop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = continuous, onCheckedChange = { continuous = it })
            }
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
