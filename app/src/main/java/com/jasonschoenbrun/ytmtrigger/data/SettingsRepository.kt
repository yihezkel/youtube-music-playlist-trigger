package com.jasonschoenbrun.ytmtrigger.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val defaultPlaylistUrls: List<String> = emptyList(),
    val defaultVolumePercent: Int? = 100,
    val defaultEnableShuffle: Boolean = true,
    val defaultSkipFirstTrack: Boolean = true,
)

private val SETTINGS_KEY = stringPreferencesKey("settings_json")

/** Initial seed for new installs. Empty by default; the user adds their
 *  playlists via the "Default settings" screen after install. */
private val INITIAL_SEED_PLAYLISTS: List<String> = emptyList()

class SettingsRepository private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        runBlocking {
            val prefs = context.dataStore.data.first()
            val raw = prefs[SETTINGS_KEY]
            val loaded = if (raw.isNullOrBlank()) {
                // First run: seed defaults with the initial playlist set.
                AppSettings(defaultPlaylistUrls = INITIAL_SEED_PLAYLISTS)
                    .also { persist(it) }
                    .also { Logger.i("Settings", "Seeded initial defaults", mapOf("count" to INITIAL_SEED_PLAYLISTS.size.toString())) }
            } else {
                runCatching { json.decodeFromString<AppSettings>(raw) }
                    .onFailure { Logger.e("Settings", "Failed to decode", t = it) }
                    .getOrDefault(AppSettings())
            }
            _flow.value = loaded
            Logger.i("Settings", "Loaded settings", mapOf(
                "defaultPlaylistCount" to loaded.defaultPlaylistUrls.size.toString(),
                "defaultVolume" to (loaded.defaultVolumePercent?.toString() ?: "null"),
            ))
        }
    }

    fun current(): AppSettings = _flow.value

    fun update(transform: (AppSettings) -> AppSettings) = scope.launch {
        val updated = transform(_flow.value)
        persist(updated)
        Logger.i("Settings", "Updated settings", mapOf(
            "defaultPlaylistCount" to updated.defaultPlaylistUrls.size.toString(),
            "defaultVolume" to (updated.defaultVolumePercent?.toString() ?: "null"),
        ))
    }

    private suspend fun persist(s: AppSettings) {
        context.dataStore.edit { it[SETTINGS_KEY] = json.encodeToString(s) }
        _flow.value = s
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
