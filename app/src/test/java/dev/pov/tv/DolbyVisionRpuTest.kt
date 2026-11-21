package dev.pov.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against dovi_tool's own samples and conversions (test resources dovi/, MIT). */
class DolbyVisionRpuTest {
    /** The RPU payload of a sample: its 4-byte start code removed. */
    private fun sample(name: String): ByteArray =
        javaClass.getResourceAsStream("/dovi/$name")!!.use { it.readBytes() }.let { it.copyOfRange(4, it.size) }

    @Test fun samplesParse() {
        assertEquals(7, DolbyVisionRpu.info(sample("fel_orig.bin"))!!.profile)
        assertEquals(true, DolbyVisionRpu.info(sample("fel_orig.bin"))!!.fel)
        assertEquals(7, DolbyVisionRpu.info(sample("mel_orig.bin"))!!.profile)
        assertEquals(false, DolbyVisionRpu.info(sample("mel_orig.bin"))!!.fel)
        assertEquals(8, DolbyVisionRpu.info(sample("profile8.bin"))!!.profile)
    }

    @Test fun melTo81MatchesDoviTool() {
        assertArrayEquals(sample("mel_to_81.bin"), DolbyVisionRpu.toProfile81(sample("mel_orig.bin")))
    }

    /** dovi_tool's fel_to_81 sample went FEL → MEL → 8.1, so its curves were kept. */
    @Test fun felTo81KeepingCurvesMatchesDoviTool() {
        assertArrayEquals(sample("fel_to_81.bin"), DolbyVisionRpu.toProfile81(sample("fel_orig.bin"), keepFelMapping = true))
    }

    @Test fun felTo81UsesIdentityCurves() {
        val converted = assertNotNullResult(DolbyVisionRpu.toProfile81(sample("fel_orig.bin")))
        val info = DolbyVisionRpu.info(converted)!!
        assertEquals(8, info.profile)
        assertTrue(info.identityMapping)
        // A Profile 8 RPU goes through unchanged, so converting twice is the same as once.
        assertArrayEquals(converted, DolbyVisionRpu.toProfile81(converted))
    }

    @Test fun profile8IsUnchangedAndBrokenDataRefused() {
        val p8 = sample("profile8.bin")
        assertArrayEquals(p8, DolbyVisionRpu.toProfile81(p8))
        val broken = sample("fel_orig.bin").also { it[20] = (it[20].toInt() xor 0x10).toByte() }
        assertNull(DolbyVisionRpu.toProfile81(broken))                     // CRC no longer matches
        assertNull(DolbyVisionRpu.toProfile81(ByteArray(3)))
    }

    @Test fun accessUnitBecomesProfile81() {
        val base = byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x11, 0x22, 0x33)             // IDR slice, layer 0
        val rpu = byteArrayOf(0, 0, 1, 0x7C, 0x01) + sample("fel_orig.bin")
        val enhancement = byteArrayOf(0, 0, 1, 0x7E, 0x01, 0x44, 0x55, 0x66)         // UNSPEC63: the enhancement layer
        val unit = base + rpu + enhancement
        val buffer = java.nio.ByteBuffer.wrap(unit.copyOf(unit.size + 64), 0, unit.size).slice()
        val size = DolbyVisionCompatibility.convertToProfile81(buffer)
        val out = ByteArray(size).also { buffer.duplicate().apply { position(0); limit(size) }.get(it) }
        val expected = base + byteArrayOf(0, 0, 1, 0x7C, 0x01) + DolbyVisionRpu.toProfile81(sample("fel_orig.bin"))!!
        assertArrayEquals(expected, out)
    }

    private fun assertNotNullResult(value: ByteArray?): ByteArray { assertNotNull(value); return value!! }
}
