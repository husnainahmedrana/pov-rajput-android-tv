package dev.pov.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Public YouTube player lookup, inspired by NuvioTV's GPL-3.0 native trailer approach.
 * https://github.com/NuvioMedia/NuvioTV
 * No user cookies, login tokens, hardcoded API keys, deciphering or restricted videos.
 */
internal class NativeTrailerSource {
    data class Audio(val url: String, val length: Long)
    data class Source(val url: String, val length: Long, val userAgent: String, val audio: Audio? = null)
    private val http = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(12,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    private var key: String? = null
    private var keyAt = 0L
    @Volatile private var blockedUntil = 0L
    private val cache = linkedMapOf<String, Pair<Long,Source>>()
    @Synchronized fun resolve(id: String): Source? {
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val now = System.currentTimeMillis()
        cache[id]?.takeIf { now - it.first in 0..1_200_000L }?.let { return it.second }
        if (now < blockedUntil) return null
        if (key == null || now - keyAt !in 0..10_800_000L) {
            val html = request(Request.Builder().url("https://www.youtube.com/watch?v=$id&hl=en").build()) ?: return null
            key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            keyAt = now
        }
        val apiKey = key ?: return null
        val client = JSONObject().put("clientName","ANDROID").put("clientVersion","20.10.35")
            .put("osName","Android").put("osVersion","14").put("androidSdkVersion",34)
            .put("platform","MOBILE").put("hl","en").put("gl","US")
        val body = JSONObject().put("videoId",id).put("context",JSONObject().put("client",client))
        val response = request(Request.Builder().url("https://www.youtube.com/youtubei/v1/player?key=${Net.encode(apiKey)}")
            .header("User-Agent",UA).header("X-Youtube-Client-Name","3")
            .header("X-Youtube-Client-Version","20.10.35")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()) ?: return null
        val source = parse(JSONObject(response)) ?: return null
        cache[id] = now to source
        while (cache.size > 12) cache.remove(cache.keys.first())
        return source
    }
    private fun request(request: Request): String? = http.newCall(request).execute().use {
        if (it.code == 429) blockedUntil = System.currentTimeMillis()+600_000
        if (it.isSuccessful) it.body?.string() else null
    }
    companion object {
        private const val UA = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip"
        fun parse(response: JSONObject): Source? {
            if (response.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
            val streaming = response.optJSONObject("streamingData") ?: return null
            val candidates = mutableListOf<Pair<Int,Source>>()
            val audio = mutableListOf<Pair<Int,Audio>>()
            for (name in listOf("adaptiveFormats","formats")) {
                val formats = streaming.optJSONArray(name) ?: continue
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val address = f.optString("url")
                    val uri = runCatching { URI(address) }.getOrNull() ?: continue
                    if (uri.scheme != "https" || uri.userInfo != null || uri.host?.endsWith(".googlevideo.com") != true) continue
                    val length = f.optLong("contentLength",-1)
                    if (length <= 0) continue
                    val mime = f.optString("mimeType")
                    val height = f.optInt("height")
                    // AAC and AVC work on a broad range of Android TVs without software decoding.
                    if (mime.startsWith("audio/mp4") && mime.contains("mp4a")) {
                        val track = f.optJSONObject("audioTrack")
                        if (track == null || track.optBoolean("audioIsDefault",true))
                            audio += f.optInt("bitrate") to Audio(address,length)
                    } else if (height in 144..720 && mime.contains("avc1")) {
                        candidates += height to Source(address,length,UA)
                    }
                }
            }
            return candidates.maxByOrNull { it.first }?.second?.copy(audio = audio.maxByOrNull { it.first }?.second)
        }
    }
}
