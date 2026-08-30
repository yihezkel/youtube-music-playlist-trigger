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
fun SchedulesScreen(onNav: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { ScheduleRepository.get(ctx) }
    val schedules by repo.flow.collectAsStateWithLifecycle()
    val settings by SettingsRepository.get(ctx).flow.collectAsStateWithLifecycle()

    fun play(s: Schedule, overrideCalendar: Boolean) {
        Logger.i("UI", "Manual trigger", mapOf(
            "scheduleId" to s.id,
            "overrideCalendar" to overrideCalendar.toString(),
        ))
        PlaybackTriggerService.startManual(ctx, s.id, overrideCalendar)
        // B-fix-5: bow out of MainActivity so it can't sit on
        // top of YT Music once the launch intent fires.
        (ctx as? android.app.Activity)?.finish()
    }

    // Non-null while the user is being asked to confirm playing during
    // Shabat / Yom Tov. Holds the reason so the dialog can name the day.
    var confirmPlay by remember { mutableStateOf<Pair<Schedule, String>?>(null) }

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
                    // A disabled schedule would not have fired anyway, so it
                    // gets no warning.
                    val wouldHitChag = s.enabled && HebrewCalendarChecker.blockedOccurrences(
                        AlarmScheduler.occurrencesWithin(ctx, s, days = 7),
                        settings.calendarConfig(),
                    ).isNotEmpty()
                    ScheduleCard(
                        schedule = s,
                        calendarWarning = if (wouldHitChag) CHAG_SCHEDULE_WARNING else null,
                        onClick = { onNav(Screen.Edit(s.id)) },
                        onPlay = {
                            val cal = HebrewCalendarChecker.check(
                                LocalDateTime.now(),
                                settings.calendarConfig(),
                            )
                            if (cal.skip) {
                                confirmPlay = s to (cal.reason ?: "Shabat/Yom Tov")
                            } else {
                                play(s, overrideCalendar = false)
                            }
                        },
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

    confirmPlay?.let { (schedule, reason) ->
        AlertDialog(
            onDismissRequest = { confirmPlay = null },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("It's $reason") },
            text = {
                Text(
                    "Music is not supposed to play on Shabat or Yom Tov. " +
                        "Play \"${schedule.name}\" anyway?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPlay = null
                    play(schedule, overrideCalendar = true)
                }) { Text("Play anyway") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPlay = null }) { Text("Cancel") }
            },
        )
    }
}

/** Exact wording requested by the user; kept in one place so both the card
 *  and any future surface stay identical. */
private const val CHAG_SCHEDULE_WARNING =
    "Warning: This schedule would've caused music to play (but it won't) " +
        "on Shabat/Yom Tov over the next week."

/**
 * One playlist entry. Shows the name on top when the entry carries one, with
 * the URL underneath in a dimmer style, so a labelled list stays scannable
 * without hiding what actually gets launched.
 */
@Composable
private fun PlaylistEntryText(entry: String, modifier: Modifier = Modifier) {
    val name = PlaylistUrl.label(entry)
    Column(modifier) {
        if (name != null) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            PlaylistUrl.url(entry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Structured editor for one entry: URL, name, and the podcast qualifiers.
 *
 * Entries are still *stored* as `url [Name | mode | min N]`, because the device
 * config, the console and the schedule tooling all share that format. Only the
 * editing changed: the qualifiers used to be free text the user had to know the
 * grammar for, and a typo silently fell back to the schedule default rather
 * than reporting anything.
 *
 * Anything the editor does not model is preserved via
 * [MediaEntries.otherQualifiers], so opening and saving an entry cannot quietly
 * discard part of it.
 */
@Composable
private fun MediaEntryRow(
    entry: String,
    onChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val parsed = remember(entry) { MediaEntries.parse(entry) }
    val extra = remember(entry) { MediaEntries.otherQualifiers(entry) }
    val isPodcast = parsed.kind == MediaKind.PodcastFeed || parsed.kind == MediaKind.SpotifyShow
    // Rebuilt from the parts rather than edited as text, so the stored grammar
    // stays in exactly one place: MediaEntries.format.
    fun emit(
        url: String = MediaEntries.url(entry),
        label: String? = parsed.label,
        mode: PodcastEpisodeMode? = parsed.episodeMode,
        min: Int? = parsed.minMinutes,
    ) = onChange(MediaEntries.format(url, label, mode, min, extra))

    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceElevated),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kindLabel(parsed.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (parsed.kind == MediaKind.Unknown) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null) }
            }
            OutlinedTextField(
                value = MediaEntries.url(entry),
                onValueChange = { emit(url = it) },
                label = { Text("URL") },
                singleLine = true,
                isError = parsed.kind == MediaKind.Unknown,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = parsed.label.orEmpty(),
                onValueChange = { emit(label = it.ifBlank { null }) },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isPodcast) {
                Text("Which episode", style = MaterialTheme.typography.labelMedium)
                // Two rows of two rather than a wrapping flow: FlowRow is still
                // experimental in this Compose version, and four fixed chips do
                // not need it.
                val modes = listOf(
                    null to "Schedule default",
                    PodcastEpisodeMode.Random to "Random",
                    PodcastEpisodeMode.Latest to "Newest",
                    PodcastEpisodeMode.Sequential to "In order",
                )
                for (pair in modes.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, label) in pair) {
                            FilterChip(
                                selected = parsed.episodeMode == mode,
                                onClick = { emit(mode = mode) },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = parsed.minMinutes?.toString().orEmpty(),
                    onValueChange = { v ->
                        emit(min = v.filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 })
                    },
                    label = { Text("Shortest episode to play, minutes (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            "For feeds carrying two formats under one name — a show with " +
                                "both 4-minute clips and hour-long interviews.",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (extra.isNotEmpty()) {
                Text(
                    "Kept as written, not understood: ${extra.joinToString(" | ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.YtmPlaylist -> "YouTube Music playlist"
    MediaKind.YtmTrack -> "YouTube Music song"
    MediaKind.PodcastFeed -> "Podcast feed"
    MediaKind.SpotifyShow -> "Spotify show"
    MediaKind.AlephBeta -> "Aleph Beta"
    MediaKind.Unknown -> "Not a recognised link"
}

/** The whole list plus its Add button, shared by the schedule and settings screens. */
@Composable
internal fun MediaEntryListEditor(
    entries: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    addLabel: String = "Add playlist, song or podcast",
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in entries.indices) {
            key(i) {
                MediaEntryRow(
                    entry = entries[i],
                    onChange = { entries[i] = it },
                    onDelete = { entries.removeAt(i) },
                )
            }
        }
        OutlinedButton(
            onClick = { entries.add("") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(addLabel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(
    schedule: Schedule,
    calendarWarning: String?,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val time = ScheduleTimes.describe(schedule)
    val nextMs = if (schedule.enabled) AlarmScheduler.computeNextTriggerMs(ctx, schedule) else null
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
                    schedule.stopTimeMinutes?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "→ %02d:%02d".format(it / 60, it % 60),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
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
                FilledTonalButton(
                    onClick = onPlay,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play now", fontWeight = FontWeight.SemiBold)
                }
                calendarWarning?.let { warning ->
                    Surface(
                        color = cs.errorContainer,
                        contentColor = cs.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Icon(
                                Icons.Default.Warning, null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(warning, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sunday first, as the week is counted in Israel. Two letters where one would
 * be ambiguous, and a shin for Shabat.
 *
 * The values stay ISO (Monday = 1 … Sunday = 7) because that is what
 * [Schedule.daysOfWeek] and every scheduling calculation use; only the order
 * and the labels shown here change.
 */
private val DAYS_DISPLAY = listOf(
    DayOfWeek.SUNDAY to "Su",
    DayOfWeek.MONDAY to "M",
    DayOfWeek.TUESDAY to "Tu",
    DayOfWeek.WEDNESDAY to "W",
    DayOfWeek.THURSDAY to "Th",
    DayOfWeek.FRIDAY to "F",
    DayOfWeek.SATURDAY to "\u05E9",
)

@Composable
internal fun DayOfWeekStrip(
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
