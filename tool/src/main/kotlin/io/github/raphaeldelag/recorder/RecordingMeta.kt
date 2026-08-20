package io.github.raphaeldelag.recorder

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Attribution terms agreed with the source, captured at save time. */
enum class Consent(val label: String) {
    NotDiscussed("NOT DISCUSSED"),
    OnRecord("ON THE RECORD"),
    OnBackground("ON BACKGROUND"),
    OffRecord("OFF THE RECORD");

    fun next(): Consent = entries[(ordinal + 1) % entries.size]
}

@Serializable
data class Marker(val ms: Long, val note: String = "")

/**
 * Sidecar written next to each recording as `<name>.json`. Everything a transcription
 * pipeline or a future self needs: when, how long, what was agreed, and where the moments are.
 */
@Serializable
data class RecordingMeta(
    val file: String,
    val recordedAt: String,            // ISO-8601 local time, from the filename
    val durationMs: Long = 0L,
    val label: String? = null,
    val consent: String = Consent.NotDiscussed.name,
    val markers: List<Marker> = emptyList(),
    val recordedWith: String = "lp3-recorder",
) {
    val consentEnum: Consent get() = runCatching { Consent.valueOf(consent) }.getOrDefault(Consent.NotDiscussed)
}

object Sidecar {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun fileFor(audio: File): File = File(audio.parentFile, audio.name.removeSuffix(RecordingRepository.EXT) + ".json")

    fun read(audio: File): RecordingMeta? {
        val f = fileFor(audio)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<RecordingMeta>(f.readText()) }.getOrNull()
    }

    fun write(audio: File, meta: RecordingMeta) {
        val f = fileFor(audio)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(meta.copy(file = audio.name)))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Load or synthesize metadata for a recording. */
    fun forRecording(rec: Recording): RecordingMeta =
        read(rec.file)?.copy(file = rec.name, label = rec.label)
            ?: RecordingMeta(file = rec.name, recordedAt = rec.recordedAt.toString(), label = rec.label)
}
