package io.github.raphaeldelag.recorder

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/** Remembers each recording's duration so the list can show it without opening every file. */
class DurationStore(private val dataStore: DataStore<Preferences>) {
    private fun key(fileName: String) = longPreferencesKey("duration_ms:$fileName")

    suspend fun get(fileName: String): Long? = dataStore.data.first()[key(fileName)]

    suspend fun all(): Map<String, Long> {
        val prefs = dataStore.data.first()
        return prefs.asMap().entries
            .filter { it.key.name.startsWith("duration_ms:") }
            .associate { it.key.name.removePrefix("duration_ms:") to (it.value as Long) }
    }

    suspend fun set(fileName: String, durationMs: Long) {
        dataStore.edit { it[key(fileName)] = durationMs }
    }

    suspend fun move(oldName: String, newName: String) {
        if (oldName == newName) return
        dataStore.edit { prefs ->
            prefs[key(oldName)]?.let { prefs[key(newName)] = it }
            prefs.remove(key(oldName))
        }
    }

    suspend fun remove(fileName: String) {
        dataStore.edit { it.remove(key(fileName)) }
    }
}
