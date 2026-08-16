package io.github.raphaeldelag.recorder

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One saved memo on disk. Filenames carry all metadata so nothing is lost if the
 * DataStore is wiped or the files are pulled to another machine:
 *   yyyyMMdd-HHmmss.m4a            (unlabelled)
 *   yyyyMMdd-HHmmss_some-label.m4a (labelled)
 */
data class Recording(
    val file: File,
    val recordedAt: LocalDateTime,
    val label: String?,
) {
    val name: String get() = file.name
    val displayTitle: String get() = label?.replace('-', ' ')?.uppercase() ?: recordedAt.format(TITLE_FORMAT)
    val displayDate: String get() = recordedAt.format(DATE_FORMAT)

    companion object {
        private val TITLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d, yyyy HH:mm")
    }
}

class RecordingRepository(filesDir: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    val recordingsDir: File = File(filesDir, "recordings")

    fun list(): List<Recording> {
        val files = recordingsDir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyList()
        return files.mapNotNull(::parse).sortedByDescending { it.recordedAt }
    }

    /** New, not-yet-existing file for a recording started at [nowMillis]. */
    fun newRecordingFile(nowMillis: Long = System.currentTimeMillis()): File {
        recordingsDir.mkdirs()
        val stamp = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone).format(STAMP_FORMAT)
        var candidate = File(recordingsDir, "$stamp$EXT")
        var n = 2
        while (candidate.exists()) {
            candidate = File(recordingsDir, "$stamp-$n$EXT")
            n++
        }
        return candidate
    }

    /**
     * Rename [recording] with a new label (blank/null clears it). Returns the updated recording,
     * or null if the rename failed. A no-op rename returns the same recording.
     */
    fun relabel(recording: Recording, newLabel: String?): Recording? {
        val slug = slugify(newLabel)
        val base = stampBase(recording.file.name)
        val target = File(recordingsDir, if (slug.isEmpty()) "$base$EXT" else "${base}_$slug$EXT")
        if (target == recording.file) return recording
        if (target.exists()) return null
        if (!recording.file.renameTo(target)) return null
        return parse(target)
    }

    fun delete(recording: Recording): Boolean = recording.file.delete()

    fun parse(file: File): Recording? {
        val name = file.name
        if (!name.endsWith(EXT)) return null
        val stem = name.removeSuffix(EXT)
        val underscore = stem.indexOf('_')
        val stampPart = if (underscore >= 0) stem.substring(0, underscore) else stem
        val label = if (underscore >= 0) stem.substring(underscore + 1).takeIf { it.isNotEmpty() } else null
        // stamp is exactly 15 chars (yyyyMMdd-HHmmss); anything after is a collision suffix like "-2"
        if (stampPart.length < 15) return null
        val stamp = stampPart.substring(0, 15)
        val recordedAt = try {
            LocalDateTime.parse(stamp, STAMP_FORMAT)
        } catch (e: DateTimeParseException) {
            return null
        }
        return Recording(file, recordedAt, label)
    }

    companion object {
        const val EXT = ".m4a"
        val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private const val MAX_SLUG = 40

        /** "Interview w/ Sheriff Ó'Neil!!" -> "interview-w-sheriff-oneil" */
        fun slugify(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val folded = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return folded.lowercase()
                .replace(Regex("['\u2019]"), "")
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(MAX_SLUG)
                .trim('-')
        }

        /** "20260816-121500_foo" -> "20260816-121500"; "20260816-121500-2_foo" -> "20260816-121500-2" */
        internal fun stampBase(fileName: String): String {
            val stem = fileName.removeSuffix(EXT)
            val underscore = stem.indexOf('_')
            return if (underscore >= 0) stem.substring(0, underscore) else stem
        }
    }
}

internal fun formatClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
