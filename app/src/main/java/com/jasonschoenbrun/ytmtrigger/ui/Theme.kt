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

private val BrandRed          = Color(0xFFFF1744)

private val BrandRedContainer = Color(0xFF8B0017)

private val Accent            = Color(0xFFB59CFF)

private val AccentContainer   = Color(0xFF4A3E80)

private val BgNeutral         = Color(0xFF101013)

private val SurfaceNeutral    = Color(0xFF15151A)

internal val SurfaceElevated   = Color(0xFF1F1F26)

private val SurfaceMuted      = Color(0xFF2A2A33)

private val OnSurface         = Color(0xFFEBE6F0)

private val OnSurfaceMuted    = Color(0xFFB5B0BD)

private val OutlineMuted      = Color(0xFF3A3A45)

internal val AppDarkColors = darkColorScheme(
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

internal val HealthGreen  = Color(0xFF3DDC84)

internal val HealthOrange = Color(0xFFFFB020)

internal val HealthRed    = Color(0xFFFF5252)
