package io.github.raphaeldelag.recorder

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Sends recordings to a receiver on the local network (scripts/recorder_receiver.py on the Mac).
 *
 * The receiver prints an address like `https://192.168.1.20:8787/<token>#<sha256 of its cert>`.
 * Android forbids plain HTTP from tools, so the receiver uses a self-signed certificate and the
 * tool pins that certificate by the fingerprint carried in the URL fragment (trust-on-scan).
 * Each file is PUT to <receiverUrl>/<filename>. Sent files are remembered in DataStore.
 */
class Uploader(private val dataStore: DataStore<Preferences>) {
    private var client: HttpClient? = null
    private var clientPin: String? = null

    /** One client per pin; rebuilt when the receiver changes. */
    private suspend fun client(): HttpClient {
        val pin = dataStore.data.first()[RECEIVER_PIN]
        client?.takeIf { clientPin == pin }?.let { return it }
        client?.close()
        val c = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 120_000
            }
            if (pin != null) {
                engine {
                    config {
                        val tm = PinnedTrustManager(pin)
                        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
                        sslSocketFactory(ctx.socketFactory, tm)
                        hostnameVerifier { _, _ -> true } // identity is the pinned certificate, not the LAN IP
                    }
                }
            }
        }
        client = c; clientPin = pin
        return c
    }

    suspend fun receiverUrl(): String? = dataStore.data.first()[RECEIVER_URL]?.takeIf { it.isNotBlank() }

    suspend fun setReceiverUrl(raw: String?) {
        dataStore.edit { prefs ->
            if (raw.isNullOrBlank()) {
                prefs.remove(RECEIVER_URL); prefs.remove(RECEIVER_PIN)
            } else {
                val (url, pin) = normalize(raw)
                prefs[RECEIVER_URL] = url
                if (pin != null) prefs[RECEIVER_PIN] = pin else prefs.remove(RECEIVER_PIN)
            }
        }
    }

    suspend fun sentAt(fileName: String): Long? = dataStore.data.first()[sentKey(fileName)]

    suspend fun sentNames(): Set<String> = dataStore.data.first().asMap().keys
        .map { it.name }.filter { it.startsWith(SENT_PREFIX) }.map { it.removePrefix(SENT_PREFIX) }.toSet()

    suspend fun forgetSent(fileName: String) { dataStore.edit { it.remove(sentKey(fileName)) } }

    suspend fun moveSent(oldName: String, newName: String) {
        if (oldName == newName) return
        dataStore.edit { prefs ->
            prefs[sentKey(oldName)]?.let { prefs[sentKey(newName)] = it }
            prefs.remove(sentKey(oldName))
        }
    }

    sealed class TranscriptResult {
        data class Ready(val text: String) : TranscriptResult()
        object Pending : TranscriptResult()
        data class Failed(val message: String) : TranscriptResult()
    }

    /** GET <receiverUrl>/transcript/<name>: the receiver transcribes locally with Whisper. */
    suspend fun fetchTranscript(fileName: String): TranscriptResult {
        val base = receiverUrl() ?: return TranscriptResult.Failed("No receiver set")
        return try {
            val response = withContext(Dispatchers.IO) { client().get("$base/transcript/$fileName") }
            when (response.status.value) {
                200 -> TranscriptResult.Ready(response.bodyAsText())
                202 -> TranscriptResult.Pending
                404 -> TranscriptResult.Failed("Not on the receiver yet — send it first")
                else -> TranscriptResult.Failed("HTTP ${response.status.value}")
            }
        } catch (e: Exception) {
            TranscriptResult.Failed(e.message ?: "Network error")
        }
    }

    /** GET <receiverUrl>/ping; the receiver answers "ok". */
    suspend fun ping(): Result<String> = runCatching {
        val base = receiverUrl() ?: error("No receiver set")
        val response = withContext(Dispatchers.IO) { client().get("$base/ping") }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        response.bodyAsText().trim().ifEmpty { "ok" }
    }

    /** PUT the audio (and its sidecar, if any); on success records the send time. */
    suspend fun send(file: File): Result<Unit> = runCatching {
        val base = receiverUrl() ?: error("No receiver set")
        putFile(base, file, "audio/mp4")
        val sidecar = Sidecar.fileFor(file)
        if (sidecar.isFile) putFile(base, sidecar, "application/json")
        dataStore.edit { it[sentKey(file.name)] = System.currentTimeMillis() }
    }

    private suspend fun putFile(base: String, file: File, contentType: String) {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val response = withContext(Dispatchers.IO) {
            client().put("$base/${file.name}") {
                header("Content-Type", contentType)
                header("X-Recording-Name", file.name)
                setBody(bytes)
            }
        }
        check(response.status.isSuccess()) { "HTTP ${response.status.value}: ${response.bodyAsText().take(80)}" }
    }

    fun close() { client?.close(); client = null }

    /** Trusts exactly one certificate: the one whose SHA-256 (hex) matches the pin from the receiver URL. */
    private class PinnedTrustManager(pinHex: String) : X509TrustManager {
        private val pin = pinHex.lowercase().replace(":", "")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("client certs unsupported")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException("empty chain")
            val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            if (fp != pin) throw java.security.cert.CertificateException("receiver certificate does not match the pinned fingerprint")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private val RECEIVER_URL = stringPreferencesKey("receiver_url")
        private val RECEIVER_PIN = stringPreferencesKey("receiver_pin")
        private const val SENT_PREFIX = "sent_at:"
        private fun sentKey(name: String) = longPreferencesKey("$SENT_PREFIX$name")

        /**
         * Trim, add https:// if no scheme, drop trailing slashes, split off a `#<sha256>` pin.
         * Returns (url, pinHexOrNull).
         */
        fun normalize(raw: String): Pair<String, String?> {
            var u = raw.trim()
            var pin: String? = null
            val hash = u.indexOf('#')
            if (hash >= 0) { pin = u.substring(hash + 1).trim().lowercase().replace(":", "").ifEmpty { null }; u = u.substring(0, hash) }
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (pin != null && !Regex("^[0-9a-f]{64}$").matches(pin)) pin = null
            return u.trimEnd('/') to pin
        }
    }
}
