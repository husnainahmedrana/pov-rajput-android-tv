package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class TorBoxUsenetTest {
    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun messageIdHashComesFirst() {
        val nzb = "<nzb><file poster=\"a@b\" subject=\"x\"><segments><segment bytes=\"1\" number=\"1\">abc@news</segment></segments></file></nzb>".toByteArray()
        val hashes = TorBoxUsenet.hashes(nzb, listOf("abc@news", "def@news"), "https://indexer.example/api?t=get&id=7&apikey=k")
        assertEquals(md5("abc@news"), hashes.first())
        assertEquals(true, md5("https://indexer.example/api?t=get&id=7&apikey=k") in hashes)
        assertEquals(md5("def@news"), hashes.last())
        assertEquals(hashes.size, hashes.distinct().size)
    }

    @Test fun cleanupRemovesBomCommentsAndPosters() {
        val raw = Char(0xFEFF) + "<!-- made by X --><nzb>\n <file poster=\"Joe &lt;j@x&gt;\" date=\"1\" subject='s'><!--c--></file></nzb>  "
        assertEquals("<nzb>\n <file date=\"1\" subject='s'></file></nzb>", String(TorBoxUsenet.clean(raw.toByteArray())))
    }

    @Test fun biggestVideoIsPlayedNotTheSample() {
        val files = listOf(CloudFile(1, "Movie.2160p.mkv", 50_000), CloudFile(2, "Movie.sample.mkv", 90_000),
            CloudFile(3, "Movie.nfo", 900_000), CloudFile(4, "Movie.part2.mkv", 40_000))
        assertEquals(1L, TorBoxUsenet.pickFile(files)!!.id)
        assertEquals(3L, TorBoxUsenet.pickFile(listOf(CloudFile(3, "Movie.nfo", 1)))!!.id)
    }
}
