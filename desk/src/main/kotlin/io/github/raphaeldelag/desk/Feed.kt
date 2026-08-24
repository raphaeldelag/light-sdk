package io.github.raphaeldelag.desk

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class FeedItem(val title: String, val date: String = "")
@Serializable data class FeedSection(
    val repo: String,
    val label: String,
    val items: List<FeedItem> = emptyList(),
    val health: String = "unknown",
    val error: String? = null,
)
@Serializable data class Digest(
    val generated: String = "",
    val window_days: Int = 7,
    val total_items: Int = 0,
    val broken: List<String> = emptyList(),
    val sections: List<FeedSection> = emptyList(),
)

/** Fetches the digest JSON from the configured URL and keeps the last good copy on disk. */
class Feed(private val dataStore: DataStore<Preferences>, filesDir: File) {
    private val cache = File(filesDir, "digest.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) { connectTimeoutMillis = 8_000; requestTimeoutMillis = 30_000 }
    }

    suspend fun url(): String? = dataStore.data.first()[URL_KEY]?.takeIf { it.isNotBlank() }

    suspend fun setUrl(raw: String?) {
        dataStore.edit { prefs ->
            if (raw.isNullOrBlank()) prefs.remove(URL_KEY)
            else prefs[URL_KEY] = raw.trim()
        }
    }

    fun cached(): Digest? = runCatching { json.decodeFromString<Digest>(cache.readText()) }.getOrNull()

    suspend fun refresh(): Result<Digest> = runCatching {
        val u = url() ?: error("No feed configured")
        val response = withContext(Dispatchers.IO) { client.get(u) }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        val body = response.bodyAsText()
        val digest = json.decodeFromString<Digest>(body)
        withContext(Dispatchers.IO) { cache.writeText(body) }
        digest
    }

    companion object { private val URL_KEY = stringPreferencesKey("feed_url") }
}
