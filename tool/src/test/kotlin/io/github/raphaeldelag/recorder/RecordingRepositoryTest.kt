package io.github.raphaeldelag.recorder

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingRepositoryTest {
    private fun tempRepo(): RecordingRepository {
        val dir = Files.createTempDirectory("recorder-test").toFile()
        return RecordingRepository(dir, ZoneOffset.UTC)
    }

    @Test
    fun `slugify folds accents, punctuation and length`() {
        assertEquals("interview-w-sheriff-oneil", RecordingRepository.slugify("Interview w/ Sheriff Ó'Neil!!"))
        assertEquals("", RecordingRepository.slugify("   "))
        assertEquals("", RecordingRepository.slugify(null))
        assertEquals("a-b", RecordingRepository.slugify("--a__b--"))
        assertTrue(RecordingRepository.slugify("x".repeat(100)).length <= 40)
    }

    @Test
    fun `new file name encodes timestamp and avoids collisions`() {
        val repo = tempRepo()
        // 2026-08-16 12:15:00 UTC
        val t = LocalDateTime.of(2026, 8, 16, 12, 15, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        val f1 = repo.newRecordingFile(t)
        assertEquals("20260816-121500.m4a", f1.name)
        f1.writeBytes(byteArrayOf(1))
        val f2 = repo.newRecordingFile(t)
        assertEquals("20260816-121500-2.m4a", f2.name)
    }

    @Test
    fun `parse round-trips labelled, unlabelled and collision-suffixed names`() {
        val repo = tempRepo()
        val a = repo.parse(File(repo.recordingsDir, "20260816-121500.m4a"))
        assertNotNull(a); assertNull(a.label); assertEquals(LocalDateTime.of(2026, 8, 16, 12, 15), a.recordedAt)
        val b = repo.parse(File(repo.recordingsDir, "20260816-121500_sheriff-call.m4a"))
        assertNotNull(b); assertEquals("sheriff-call", b.label); assertEquals("SHERIFF CALL", b.displayTitle)
        val c = repo.parse(File(repo.recordingsDir, "20260816-121500-2_x.m4a"))
        assertNotNull(c); assertEquals("x", c.label); assertEquals(LocalDateTime.of(2026, 8, 16, 12, 15), c.recordedAt)
        assertNull(repo.parse(File(repo.recordingsDir, "garbage.m4a")))
        assertNull(repo.parse(File(repo.recordingsDir, "20260816-121500.wav")))
    }

    @Test
    fun `list sorts newest first and ignores foreign files`() {
        val repo = tempRepo()
        repo.recordingsDir.mkdirs()
        File(repo.recordingsDir, "20260816-090000.m4a").writeBytes(byteArrayOf(1))
        File(repo.recordingsDir, "20260816-120000_later.m4a").writeBytes(byteArrayOf(1))
        File(repo.recordingsDir, "notes.txt").writeText("x")
        val names = repo.list().map { it.name }
        assertEquals(listOf("20260816-120000_later.m4a", "20260816-090000.m4a"), names)
    }

    @Test
    fun `relabel renames on disk, keeps stamp, clears with blank, refuses clobber`() {
        val repo = tempRepo()
        repo.recordingsDir.mkdirs()
        val f = File(repo.recordingsDir, "20260816-121500-2.m4a").also { it.writeBytes(byteArrayOf(1)) }
        val rec = repo.parse(f)!!
        val renamed = repo.relabel(rec, "Court hearing, Day 1")!!
        assertEquals("20260816-121500-2_court-hearing-day-1.m4a", renamed.name)
        assertTrue(renamed.file.exists()); assertFalse(f.exists())
        val cleared = repo.relabel(renamed, "  ")!!
        assertEquals("20260816-121500-2.m4a", cleared.name)
        // clobber protection
        File(repo.recordingsDir, "20260816-121500-2_taken.m4a").writeBytes(byteArrayOf(1))
        assertNull(repo.relabel(cleared, "taken"))
        // no-op
        assertEquals(cleared, repo.relabel(cleared, null))
    }

    @Test
    fun `formatClock`() {
        assertEquals("0:00", formatClock(0)); assertEquals("0:59", formatClock(59_999)); assertEquals("12:05", formatClock(725_000)); assertEquals("0:00", formatClock(-5))
    }
}
