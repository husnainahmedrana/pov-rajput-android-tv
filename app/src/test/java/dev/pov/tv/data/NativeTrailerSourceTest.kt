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
    private fun format(height:Int,host:String="r1.googlevideo.com",codec:String="avc1",scheme:String="https") = """{"height":$height,"mimeType":"video/mp4; codecs=$codec","url":"$scheme://$host/videoplayback?a=b","contentLength":"9000"}"""
    @Test fun `prefers 720p avc and rejects unsupported codecs and 4k`() {
        val selected=NativeTrailerSource.parse(payload(formats=listOf(format(360),format(720),format(2160),format(720,codec="av01")).joinToString()))
        assertNotNull(selected);assertEquals(9000L,selected!!.length)
        assertNotNull(NativeTrailerSource.parse(payload(formats=format(360))))
    }
    @Test fun `requires playable public https media target and known file length`() {
        assertNull(NativeTrailerSource.parse(payload("LOGIN_REQUIRED",format(720))))
        assertNull(NativeTrailerSource.parse(payload(formats=format(720,host="googlevideo.com.evil.example"))))
        assertNull(NativeTrailerSource.parse(payload(formats=format(720,scheme="http"))))
        assertNull(NativeTrailerSource.parse(payload(formats=format(720).replace("9000","-1"))))
        assertNull(NativeTrailerSource.parse(payload(formats="")))
    }
}
