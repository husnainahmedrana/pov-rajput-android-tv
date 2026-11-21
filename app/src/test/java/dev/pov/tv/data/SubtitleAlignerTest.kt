package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SubtitleAlignerTest {
    /** A film's worth of lines at irregular times, like real dialogue. */
    private fun reference(count: Int = 400, seed: Int = 7): List<SubtitleAligner.Line> {
        val random = java.util.Random(seed.toLong())
        var at = 60_000L
        return List(count) { i ->
            at += 2_000 + random.nextInt(9_000)
            val length = 1_200L + random.nextInt(3_500)
            SubtitleAligner.Line(at, at + length, "line $i")
        }
    }

    private fun shifted(lines: List<SubtitleAligner.Line>, offset: Long, factor: Double = 1.0, text: (Int) -> String = { "other $it" }) =
        lines.mapIndexed { i, line ->
            // The subtitle's own times: the reference's undone by new = old × factor + offset.
            SubtitleAligner.Line(((line.startMs - offset) / factor).toLong(), ((line.endMs - offset) / factor).toLong(), text(i))
        }

    @Test fun aPlainShiftIsFoundByTiming() {
        val r = reference()
        val result = SubtitleAligner.align(shifted(r, 2_350), r)
        assertNotNull(result)
        assertTrue(abs(result!!.offsetMs - 2_350) <= 50)
        assertEquals(1.0, result.factor, 0.0001)
    }

    @Test fun aFrameRateDriftIsFound() {
        val r = reference()
        val factor = 25.0 / 23.976
        val result = SubtitleAligner.align(shifted(r, -1_200, factor), r)!!
        assertEquals(factor, result.factor, 0.0001)
        assertTrue(abs(result.offsetMs + 1_200) <= 100)
    }

    @Test fun sharedWordsVoteFirst() {
        val r = reference().mapIndexed { i, line -> line.copy(text = "Name$i word${i * 7}") }
        val x = shifted(r, 4_000) { i -> "Name$i otherword${i * 3}" }
        val result = SubtitleAligner.align(x, r)!!
        assertTrue(result.byWords)
        assertEquals(4_000L, result.offsetMs)
    }

    @Test fun anUnrelatedSubtitleIsLeftAlone() {
        assertNull(SubtitleAligner.align(reference(seed = 1), reference(seed = 99)))
        assertNull(SubtitleAligner.align(reference(count = 10), reference(count = 10)))
    }

    @Test fun srtVttAndAssAreRead() {
        val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\nHello <i>there</i>\r\n\r\n2\r\n00:01:02,000 --> 00:01:04,250\r\nSecond\r\nline\r\n"
        assertEquals(listOf(SubtitleAligner.Line(1_500, 3_000, "Hello <i>there</i>"), SubtitleAligner.Line(62_000, 64_250, "Second line")),
            SubtitleAligner.parse(srt))
        val vtt = "WEBVTT\n\n00:05.000 --> 00:06.500\nShort form\n"
        assertEquals(5_000L, SubtitleAligner.parse(vtt).single().startMs)
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:10.50,0:00:12.00,Default,,0,0,0,,{\\i1}Hi,\\Nthere\n"
        assertEquals(SubtitleAligner.Line(10_500, 12_000, "Hi, there"), SubtitleAligner.parse(ass).single())
    }

    @Test fun theOpenSubtitlesHashAddsSizeAndWords() {
        val zeros = ByteArray(64 * 1024)
        assertEquals("0000000000020000", SubtitleAligner.movieHash(131_072, zeros, zeros))
        val one = ByteArray(64 * 1024).also { it[0] = 1 }
        assertEquals("0000000000020002", SubtitleAligner.movieHash(131_072, one, one))
    }
}
