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
