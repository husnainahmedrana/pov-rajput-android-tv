package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Torrent names as each add-on really sends them (From S01E01, 2026-11-17). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TorrentNameTest {
    @Test fun `torrentio and its forks - first line of title`() {
        val s = JSONObject("""{"infoHash":"a","title":"From [S01] (2022) WEB-DL 1080p | LostFilm
From.S01E01.Long.Days.Journey.Into.Night.mkv
1 5 GB","behaviorHints":{"filename":"From.S01E01.Long.Days.Journey.Into.Night.mkv"}}""")
        assertEquals("From [S01] (2022) WEB-DL 1080p | LostFilm", torrentNameOf(s, "From.S01E01.Long.Days.Journey.Into.Night.mkv"))
    }
    @Test fun `meteor - folderName`() {
        val s = JSONObject("""{"infoHash":"a","description":"F From.S01E01.2160p.WEB-DL.DDP5.1.H.265-TEPES.mkv
2160p | webdl","behaviorHints":{"filename":"From.S01E01.2160p.WEB-DL.DDP5.1.H.265-TEPES.mkv","folderName":"From S01 2160p WEB-DL DDP5 1 x265-TEPES[rartv]"}}""")
        assertEquals("From S01 2160p WEB-DL DDP5 1 x265-TEPES[rartv]", torrentNameOf(s, "From.S01E01.2160p.WEB-DL.DDP5.1.H.265-TEPES.mkv"))
    }
    @Test fun `comet and stremthru torz - formatted descriptions are never read as a name`() {
        val torz = JSONObject("""{"infoHash":"a","description":"WEB-DL HEVC
DDP | 5.1","behaviorHints":{"filename":"FROM.S01E01.2160p.MGMP.WEB.DL.mkv"}}""")
        assertEquals("", torrentNameOf(torz, "FROM.S01E01.2160p.MGMP.WEB.DL.mkv"))
        val comet = JSONObject("""{"infoHash":"a","description":"F FROM.S01E01.2160p.WEB.DV.mkv
hevc
WEB-DL | R&H","behaviorHints":{"filename":"FROM.S01E01.2160p.WEB.DV.mkv"}}""")
        assertEquals("", torrentNameOf(comet, "FROM.S01E01.2160p.WEB.DV.mkv"))
    }
}
