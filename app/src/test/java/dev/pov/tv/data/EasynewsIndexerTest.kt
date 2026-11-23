package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EasynewsIndexerTest {
    private val search = """{"downURL":"https://members.easynews.com/dl","dlFarm":"auto","dlPort":"443","data":[
        {"0":"abc123","10":"Some.Movie.2024.2160p.WEB-DL","11":".mkv","14":"2h 1m","rawSize":16000000000,"sig":"S1G&x=1","alangs":["eng"]},
        {"0":"nosig","10":"Some.Movie.2024.1080p.WEB-DL","11":".mkv","14":"2h 1m","rawSize":8000000000}]}"""

    private val indexer = EasynewsIndexer.create("Easynews", " me@example.com ", "p@ss word")
    private fun api(seen: MutableList<Pair<String, String>>) = { login: Pair<String, String> ->
        EasynewsApi({ login }, { url, headers -> seen += url to headers.getValue("Authorization"); search }, year = { 2024 })
    }

    @Test fun `the entry is a valid indexer whose key holds the login`() {
        assertTrue(EasynewsIndexer.isEasynews(indexer))
        assertEquals(EasynewsIndexer.ADDRESS, indexer.url)
        assertFalse(indexer.apiKey.any { it <= ' ' })
        assertEquals("me@example.com" to "p@ss word", EasynewsIndexer.credentials(indexer))
        assertFalse(EasynewsIndexer.isEasynews(NewznabIndexer("x", "Geek", "https://api.nzbgeek.info/api", "K")))
        assertNull(EasynewsIndexer.credentials(indexer.copy(apiKey = "not*base64")))
        try { EasynewsIndexer.create("Easynews", "", "x"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `results become Usenet links on the made-up host, never Easynews web links`() {
        val seen = mutableListOf<Pair<String, String>>()
        val items = EasynewsIndexer.search(indexer, PlaybackIdentity("tt1", "movie", "Some Movie"), "newshosting", api(seen))
        // The file without a signature has no article list: left out.
        val item = items.single()
        assertTrue(UsenetLinks.isNzb(item.directUrl))
        val link = UsenetLinks.nzbAddress(item.directUrl)
        assertTrue(link, EasynewsIndexer.isLink(link))
        assertFalse(Easynews.isHost(item.directUrl))
        assertTrue(item.usenet)
        assertEquals("Easynews", item.indexer)
        assertEquals("newshosting", item.newsProvider)
        assertEquals(16000000000L, item.sizeBytes)
        assertTrue(item.detail, item.detail.startsWith("Usenet · Easynews · 2h 1m") && "🌐 English" in item.detail)
        // The search went to Easynews with the entry's own login.
        assertTrue(seen.single().first.startsWith("https://members.easynews.com/"))
        assertEquals(Easynews.auth("me@example.com", "p@ss word").getValue("Authorization"), seen.single().second)
    }

    @Test fun `playing a link asks Easynews for that file's article list with the entry's login`() {
        val item = EasynewsIndexer.search(indexer, PlaybackIdentity("tt1", "movie", "Some Movie"), "", api(mutableListOf())).single()
        var asked: EasynewsNzb.Token? = null
        var auth: Map<String, String>? = null
        val text = EasynewsIndexer.nzb(UsenetLinks.nzbAddress(item.directUrl), indexer) { token, headers -> asked = token; auth = headers; "<nzb><segment/></nzb>" }
        assertEquals("<nzb><segment/></nzb>", text)
        assertEquals("abc123", asked!!.hash); assertEquals("Some.Movie.2024.2160p.WEB-DL", asked!!.name)
        assertEquals(".mkv", asked!!.ext); assertEquals("S1G&x=1", asked!!.sig)
        assertEquals(Easynews.auth("me@example.com", "p@ss word"), auth)
        try { EasynewsIndexer.nzb(UsenetLinks.nzbAddress(item.directUrl), indexer) { _, _ -> null }; fail() } catch (_: ApiException) {}
        try { EasynewsIndexer.nzb(UsenetLinks.nzbAddress(item.directUrl), null) { _, _ -> "x" }; fail() } catch (_: ApiException) {}
    }

    @Test fun `the indexers list finds the entry for its links and keeps the login across saves`() {
        val saved = mutableMapOf<String, String>()
        val config = NewznabIndexers({ saved[it].orEmpty() }, { key, value -> saved[key] = value })
        config.save(listOf(indexer))
        val item = EasynewsIndexer.search(indexer, PlaybackIdentity("tt1", "movie", "Some Movie"), "", api(mutableListOf())).single()
        val found = config.forLink(UsenetLinks.nzbAddress(item.directUrl))
        assertEquals(indexer.id, found?.id)
        assertEquals("me@example.com" to "p@ss word", found?.let(EasynewsIndexer::credentials))
    }
}
