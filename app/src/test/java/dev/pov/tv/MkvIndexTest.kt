package dev.pov.tv

import dev.pov.tv.usenet.MkvIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MkvIndexTest {
    companion object {
        /** EBML header, a Segment of unknown size, a Void, then a SeekHead listing Info and the Cues at [cues]. */
        fun head(cues: Long, unknownSegment: Boolean = true, voidFirst: Boolean = true): ByteArray {
            fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()
            fun be(value: Long, length: Int) = ByteArray(length) { ((value shr (8 * (length - 1 - it))) and 0xFF).toByte() }
            fun element(id: ByteArray, body: ByteArray) = id + bytes(0x80 or body.size) + body
            val ebml = element(bytes(0x1A, 0x45, 0xDF, 0xA3), element(bytes(0x42, 0x82), "matroska".toByteArray()))
            fun seek(id: ByteArray, position: Long) = element(bytes(0x4D, 0xBB), element(bytes(0x53, 0xAB), id) + element(bytes(0x53, 0xAC), be(position, 4)))
            val seekHead = element(bytes(0x11, 0x4D, 0x9B, 0x74), seek(bytes(0x15, 0x49, 0xA9, 0x66), 0x40) + seek(bytes(0x1C, 0x53, 0xBB, 0x6B), cues))
            val void = if (voidFirst) bytes(0xEC, 0x83, 0, 0, 0) else ByteArray(0)
            val size = if (unknownSegment) bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) else bytes(0x01, 0, 0, 0, 0x10, 0, 0, 0)
            return ebml + bytes(0x18, 0x53, 0x80, 0x67) + size + void + seekHead + ByteArray(64)
        }
        /** Where the Segment's data starts in [head]: the SeekHead's positions count from here. */
        fun segmentStart(): Long = (3 + 1 + 2 + 1 + 8 + 1).toLong() + 4 + 8
    }

    @Test fun `the Cues position comes from the SeekHead, counted from the Segment's start`() {
        val head = head(0x123456)
        assertEquals(segmentStart() + 0x123456, MkvIndex.cuesPosition(head, head.size, 100_000_000))
    }

    @Test fun `a known Segment size and no Void work too`() {
        val head = head(0x2000, unknownSegment = false, voidFirst = false)
        assertEquals(segmentStart() + 0x2000, MkvIndex.cuesPosition(head, head.size, 100_000_000))
    }

    @Test fun `a position past the end of the file, a cut-off head or a non-MKV give nothing`() {
        val head = head(0x123456)
        assertNull(MkvIndex.cuesPosition(head, head.size, 1000))
        assertNull(MkvIndex.cuesPosition(head, 30, 100_000_000))
        assertNull(MkvIndex.cuesPosition(ByteArray(200) { 7 }, 200, 100_000_000))
    }
}
