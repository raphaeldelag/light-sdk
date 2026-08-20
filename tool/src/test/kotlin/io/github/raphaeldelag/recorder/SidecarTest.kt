package io.github.raphaeldelag.recorder

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarTest {
    private fun repo(): RecordingRepository = RecordingRepository(Files.createTempDirectory("sidecar").toFile(), ZoneOffset.UTC)

    @Test
    fun `round trip with markers and consent`() {
        val r = repo(); r.recordingsDir.mkdirs()
        val audio = File(r.recordingsDir, "20260819-101500.m4a").also { it.writeBytes(byteArrayOf(1)) }
        val rec = r.parse(audio)!!
        assertNull(Sidecar.read(audio))
        val meta = Sidecar.forRecording(rec).copy(durationMs = 65_000, consent = Consent.OnBackground.name, markers = listOf(Marker(1200), Marker(30_500, "quote")))
        Sidecar.write(audio, meta)
        val back = Sidecar.read(audio)!!
        assertEquals("20260819-101500.m4a", back.file); assertEquals(65_000, back.durationMs)
        assertEquals(Consent.OnBackground, back.consentEnum); assertEquals(2, back.markers.size); assertEquals("quote", back.markers[1].note)
        assertEquals("2026-08-19T10:15", back.recordedAt)
        // sidecar is not listed as a recording
        assertEquals(1, r.list().size)
    }

    @Test
    fun `relabel moves the sidecar and updates its label, delete removes both`() {
        val r = repo(); r.recordingsDir.mkdirs()
        val audio = File(r.recordingsDir, "20260819-101500.m4a").also { it.writeBytes(byteArrayOf(1)) }
        val rec = r.parse(audio)!!
        Sidecar.write(audio, Sidecar.forRecording(rec).copy(markers = listOf(Marker(5))))
        val renamed = r.relabel(rec, "Sheriff call")!!
        assertFalse(Sidecar.fileFor(audio).exists())
        val moved = Sidecar.read(renamed.file)
        assertNotNull(moved); assertEquals("sheriff-call", moved.label); assertEquals(1, moved.markers.size); assertEquals(renamed.name, moved.file)
        assertTrue(r.delete(renamed))
        assertFalse(Sidecar.fileFor(renamed.file).exists()); assertFalse(renamed.file.exists())
    }

    @Test
    fun `consent cycles through all values`() {
        var c = Consent.NotDiscussed
        val seen = mutableSetOf<Consent>()
        repeat(Consent.entries.size) { seen.add(c); c = c.next() }
        assertEquals(Consent.entries.toSet(), seen); assertEquals(Consent.NotDiscussed, c)
    }
}
