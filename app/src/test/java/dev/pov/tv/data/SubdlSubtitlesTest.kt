package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubdlSubtitlesTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()

    @Test fun `SubDL subtitles are unzipped next to the others, the episode's own file from a season pack`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "subdl-test-${System.nanoTime()}")
        val calls = mutableListOf<String>()
        val finder = ExternalSubtitles(fetch = { url ->
            calls += url
            when {
                "opensubtitles" in url -> """{"subtitles":[]}"""
                "api.subdl.com" in url -> """{"status":true,"subtitles":[
                    {"release_name":"Show.S01.1080p.WEB-DL","language":"ES","url":"/subtitle/1-2.zip?api_key=k"}]}"""
                else -> error(url)
            }
        }, cacheDir = dir, download = { zip("Show.S01E01.srt" to "1\n00:00:01,000 --> 00:00:02,000\nUno\n", "Show.S01E02.srt" to "1\n00:00:01,000 --> 00:00:02,000\nDos\n") })
        val found = finder.find(PlaybackIdentity("tt0944947", "series", "Show", 1, 2), "es", "Show.S01E02.1080p.WEB-DL.mkv", subdlKey = "k")
        val subtitle = found.single()
        assertEquals("Spanish · SubDL", subtitle.label)
        assertTrue(File(subtitle.url.removePrefix("file://")).readText().contains("Dos"))
        assertTrue(calls.any { "api.subdl.com" in it && "season_number=1&episode_number=2" in it && "languages=ES" in it })
        dir.deleteRecursively()
    }

    @Test fun `no SubDL key, no SubDL request`() {
        val calls = mutableListOf<String>()
        ExternalSubtitles(fetch = { url -> calls += url; """{"subtitles":[]}""" }, cacheDir = File("x")).find(PlaybackIdentity("tt1375666", "movie", "Inception"), "es", "Inception.mkv")
        assertTrue(calls.none { "subdl" in it })
    }
}
