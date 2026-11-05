package dev.pov.tv.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class HeroTrailerCatalogTest {
    @Test fun `only valid YouTube identifiers are accepted and duplicates are merged`() {
        val result=HeroTrailerCatalog.parse(JSONObject("""{"meta":{"trailers":[{"source":"cdx31ak4KbQ","type":"Trailer"},{"source":"https://example.com/private","type":"Trailer"},{"source":"JE9z-gy4De4","type":"Featurette"}],"trailerStreams":[{"ytId":"cdx31ak4KbQ"},{"ytId":"VFkjBy2b50Q"},{"ytId":"bad'id"}]}}"""))
        assertEquals(listOf("cdx31ak4KbQ","VFkjBy2b50Q"),result)
    }
    @Test fun `unsupported identities make no request`() {
        val catalog=HeroTrailerCatalog({error("Unexpected network")})
        assertTrue(catalog.videos(MediaItemData("not-imdb","movie","Example")).isEmpty())
        assertTrue(catalog.videos(MediaItemData("tt1375666","anime","Example")).isEmpty())
    }
    @Test fun `positive results are cached with media type separation and expiration`() {
        var calls=0;var clock=100L
        val catalog=HeroTrailerCatalog({ calls++;JSONObject("""{"meta":{"trailers":[{"source":"cdx31ak4KbQ","type":"Trailer"}]}}""") },{clock})
        val item=MediaItemData("tt1375666","movie","Example")
        catalog.videos(item);catalog.videos(item);assertEquals(1,calls)
        catalog.videos(item.copy(type="series"));assertEquals(2,calls)
        clock+=21_600_001L;catalog.videos(item);assertEquals(3,calls)
    }
    @Test fun `missing trailers have a shorter cache and clock rollback expires entries`() {
        var calls=0;var clock=100L
        val catalog=HeroTrailerCatalog({calls++;JSONObject()}, {clock})
        val item=MediaItemData("tt1375666","movie","Example")
        catalog.videos(item);catalog.videos(item);assertEquals(1,calls)
        clock+=600_001L;catalog.videos(item);assertEquals(2,calls)
        clock=0;catalog.videos(item);assertEquals(3,calls)
    }
    @Test fun `candidate fallback list is bounded`() {
        val result=HeroTrailerCatalog.parse(JSONObject("""{"meta":{"trailerStreams":[{"ytId":"aaaaaaaaaaa"},{"ytId":"bbbbbbbbbbb"},{"ytId":"ccccccccccc"},{"ytId":"ddddddddddd"}]}}"""))
        assertEquals(3,result.size)
    }
}
