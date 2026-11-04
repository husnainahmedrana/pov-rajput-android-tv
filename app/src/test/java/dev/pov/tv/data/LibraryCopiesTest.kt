package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCopiesTest {
    @Test fun `one library file listed twice by AIOStreams stays once, keeping the copy with its indexer`() {
        val fromIndexer = SourceItem("the.mongoose.2026.italian.multi.hdr.2160p.web.h265-ntropic.mkv", "Hunny Usenet", directUrl = "https://aio.example/a",
            sizeBytes = 15_500_000_000L, addon = AddonBadges.AIOSTREAMS, usenet = true, indexer = "usenet-crawler", inLibrary = true)
        val libraryCopy = fromIndexer.copy(title = "The.Mongoose.2026.iTALiAN.MULTi.HDR.2160p.WEB.H265-NTROPiC-FTP", directUrl = "https://aio.example/b", indexer = "")
        val other = fromIndexer.copy(title = "The.Mongoose.2026.2160p.WEB-DL.mkv", directUrl = "https://aio.example/c", sizeBytes = 16_990_000_000L)
        val notLibrary = libraryCopy.copy(directUrl = "https://aio.example/d", inLibrary = false)
        assertEquals(listOf(fromIndexer, other, notLibrary), LibraryCopies.merge(listOf(libraryCopy, fromIndexer, other, notLibrary)))
    }
}
