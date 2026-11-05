package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class NativeTrailerSourceTest {
    private fun payload(status:String="OK",formats:String) = JSONObject("""{"playabilityStatus":{"status":"$status"},"streamingData":{"adaptiveFormats":[$formats]}}""")
    private fun format(height:Int,host:String="r1.googlevideo.com",codec:String="avc1",scheme:String="https") = """{"height":$height,"mimeType":"video/mp4; codecs=$codec","url":"$scheme://$host/videoplayback?h=$height","contentLength":"9000"}"""
    @Test fun `prefers 720p avc and rejects unsupported codecs and 4k`() {
        val selected=NativeTrailerSource.parse(payload(formats=listOf(format(360),format(720),format(2160),format(720,codec="av01")).joinToString()))
        assertNotNull(selected);assertEquals(9000L,selected!!.length);assertTrue(selected.url.endsWith("h=720"))
        assertNotNull(NativeTrailerSource.parse(payload(formats=format(360))))
    }
    private fun webm(height:Int,codec:String="vp9") = """{"height":$height,"mimeType":"video/webm; codecs=\"$codec\"","url":"https://r1.googlevideo.com/videoplayback?vp9=$height","contentLength":"9000"}"""
    @Test fun `prefers 720p vp9 only when hardware vp9 is allowed`() {
        val formats=listOf(format(720),webm(720),webm(480),webm(720,"vp09.02.51.10")).joinToString()
        assertTrue(NativeTrailerSource.parse(payload(formats=formats),vp9=true)!!.url.endsWith("vp9=720"))
        assertTrue(NativeTrailerSource.parse(payload(formats=formats))!!.url.endsWith("h=720"))
        assertTrue(NativeTrailerSource.parse(payload(formats=listOf(format(720),webm(480)).joinToString()),vp9=true)!!.url.endsWith("h=720"))
        assertNull(NativeTrailerSource.parse(payload(formats=webm(720,"vp09.02.51.10")),vp9=true))
    }
    @Test fun `requires playable public https media target and known file length`() {
        assertNull(NativeTrailerSource.parse(payload("LOGIN_REQUIRED",format(720))))
        assertNull(NativeTrailerSource.parse(payload(formats=format(720,host="googlevideo.com.evil.example"))))
        assertNull(NativeTrailerSource.parse(payload(formats=format(720,scheme="http"))))
        assertNull(NativeTrailerSource.parse(payload(formats=format(720).replace("9000","-1"))))
        assertNull(NativeTrailerSource.parse(payload(formats="")))
    }
    private fun audio(host:String="r1.googlevideo.com",default:Boolean=true,bitrate:Int=128000) =
        """{"mimeType":"audio/mp4; codecs=mp4a.40.2","url":"https://$host/audio?bitrate=$bitrate","contentLength":"1000","bitrate":$bitrate,"audioTrack":{"audioIsDefault":$default}}"""
    @Test fun `pairs video with default AAC audio instead of alternate language`() {
        val selected=NativeTrailerSource.parse(payload(formats=listOf(format(720),audio(bitrate=128000),audio(default=false,bitrate=256000)).joinToString()))!!
        assertEquals(1000L,selected.audio!!.length)
        assertTrue(selected.audio!!.url.endsWith("128000"))
    }
    @Test fun `audio also requires trusted media host and a valid length`() {
        val selected=NativeTrailerSource.parse(payload(formats=listOf(format(720),audio(host="googlevideo.com.evil.example"),audio().replace("1000","-1")).joinToString()))!!
        assertNull(selected.audio)
    }
}
