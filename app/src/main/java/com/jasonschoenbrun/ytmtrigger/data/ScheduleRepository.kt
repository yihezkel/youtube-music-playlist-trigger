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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val SCHEDULES_KEY = stringPreferencesKey("schedules_json")

class ScheduleRepository private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _flow = MutableStateFlow<List<Schedule>>(emptyList())
    val flow: StateFlow<List<Schedule>> = _flow.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * Serializes every read-modify-write below.
     *
     * Each mutation reads [_flow], then suspends inside `dataStore.edit`
     * before writing the result back. Without this lock two mutations issued
     * close together both read the pre-update list and the second silently
     * discards the first. That is not hypothetical: applying a remote config
     * upserts one schedule per call, and an 8 ms gap between two of them was
     * enough to lose the first schedule's edits entirely.
     */
    private val mutex = Mutex()

    init {
        // Load synchronously on first access so alarm-rearm at boot has data.
        runBlocking {
            val prefs = context.dataStore.data.first()
            val raw = prefs[SCHEDULES_KEY]
            _flow.value = if (raw.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString<List<Schedule>>(raw) }
                .onFailure { Logger.e("Repo", "Failed to decode schedules", t = it) }
                .getOrDefault(emptyList())
            Logger.i("Repo", "Loaded schedules", mapOf("count" to _flow.value.size.toString()))
        }
    }

    fun all(): List<Schedule> = _flow.value
    fun byId(id: String): Schedule? = _flow.value.find { it.id == id }

    fun upsert(schedule: Schedule) = scope.launch {
        mutex.withLock {
            val list = _flow.value.toMutableList()
            val idx = list.indexOfFirst { it.id == schedule.id }
            if (idx >= 0) list[idx] = schedule else list.add(schedule)
            persist(list)
        }
        Logger.i("Repo", "Upsert schedule", mapOf("id" to schedule.id, "name" to schedule.name, "enabled" to schedule.enabled.toString()))
    }

    /**
     * Replace the whole list in one atomic step.
     *
     * The remote-config path needs this: expressing "here is the new set of
     * schedules" as a delete-then-upsert-each loop is what exposed the lost
     * update above, and it is also simply the wrong shape for a wholesale
     * replacement.
     */
    fun replaceAll(schedules: List<Schedule>) = scope.launch {
        mutex.withLock { persist(schedules) }
        Logger.i("Repo", "Replaced schedules", mapOf("count" to schedules.size.toString()))
    }

    fun delete(id: String) = scope.launch {
        mutex.withLock { persist(_flow.value.filterNot { it.id == id }) }
        Logger.i("Repo", "Delete schedule", mapOf("id" to id))
    }

    fun recordPlayed(scheduleId: String, playlistId: String) = scope.launch {
        mutex.withLock {
            val list = _flow.value.map {
                if (it.id != scheduleId) it
                else {
                    val keep = (listOf(playlistId) + it.lastPickedPlaylistIds).distinct().take(3)
                    it.copy(lastPickedPlaylistIds = keep)
                }
            }
            persist(list)
        }
    }

    private suspend fun persist(list: List<Schedule>) {
        context.dataStore.edit { it[SCHEDULES_KEY] = json.encodeToString(list) }
        _flow.value = list
    }

    companion object {
        @Volatile private var instance: ScheduleRepository? = null
        fun get(context: Context): ScheduleRepository =
            instance ?: synchronized(this) {
                instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
            }
    }
}
