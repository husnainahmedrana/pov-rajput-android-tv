package dev.pov.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resuming in Kodi. Kodi ignores a start position handed over by another app, so once it is playing the stream Nabrio
 * serves, Nabrio asks Kodi's own remote control (JSON-RPC on this device, TCP port 9090) to jump to where the viewer
 * left off. That is Kodi's "Allow remote control from applications on this system" (Settings → Services → Control), on
 * by default; when it is off, the viewer is told once how to turn it on.
 */
internal object KodiResume {
    const val PACKAGE = "org.xbmc.kodi"
    private const val PORT = 9090
    private const val PREFS = "pov_player"
    private const val TOLD = "kodi_resume_told"

    /** After Kodi starts reading [server]'s stream, seeks it to [resumeMs]. Gives up quietly after a minute. */
    fun start(context: Context, server: LocalStreamServer, resumeMs: Long) {
        if (resumeMs < 30_000) return
        val app = context.applicationContext
        Thread({
            val deadline = SystemClock.elapsedRealtime() + 60_000
            // Kodi is playing once it has read the start of the file.
            while (server.playhead < 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(500)
            if (server.playhead < 0) return@Thread
            while (SystemClock.elapsedRealtime() < deadline) {
                val result = runCatching { seek(resumeMs) }
                when {
                    result.getOrNull() == true -> { android.util.Log.i("PovExternal", "Kodi resumed at $resumeMs ms"); return@Thread }
                    result.exceptionOrNull() is java.net.ConnectException -> { tellOnce(app); return@Thread }
                }
                Thread.sleep(1_000)                                    // Kodi's player isn't active yet: ask again
            }
        }, "kodi-resume").apply { isDaemon = true }.start()
    }

    /**
     * Back to Nabrio when the viewer stops the video in Kodi: Kodi announces Player.OnStop on its remote-control socket.
     * With "Display over other apps" Nabrio comes to the front and Kodi keeps running (the next video opens fast);
     * without it Kodi is asked to close, and Android shows Nabrio's page underneath. Gives up when Kodi can't be reached.
     */
    fun watchStop(context: Context, generation: () -> Boolean, onStop: () -> Unit) {
        val app = context.applicationContext
        Thread({
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var socket: java.net.Socket? = null
            while (socket == null && SystemClock.elapsedRealtime() < deadline && generation()) {
                socket = runCatching { java.net.Socket().apply { connect(java.net.InetSocketAddress("127.0.0.1", PORT), 2_000) } }.getOrNull()
                if (socket == null) Thread.sleep(1_000)
            }
            socket ?: return@Thread
            try {
                socket.soTimeout = 0
                val input = socket.getInputStream().bufferedReader()
                val current = StringBuilder()
                var depth = 0; var quoted = false; var escaped = false
                while (generation()) {
                    val code = input.read()
                    if (code < 0) return@Thread                                // Kodi closed
                    val ch = code.toChar()
                    if (depth > 0 || ch == '{') current.append(ch)
                    when {
                        escaped -> escaped = false
                        quoted && ch == '\\' -> escaped = true
                        ch == '"' -> quoted = !quoted
                        !quoted && ch == '{' -> depth++
                        !quoted && ch == '}' -> {
                            depth--
                            if (depth == 0) {
                                val message = runCatching { JSONObject(current.toString()) }.getOrNull()
                                current.setLength(0)
                                if (message?.optString("method") == "Player.OnStop") {
                                    android.util.Log.i("PovExternal", "Kodi stopped the video")
                                    if (ExternalPlayer.canReturn(app)) onStop()
                                    else runCatching { call(JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "Application.Quit")) }
                                    return@Thread
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally { runCatching { socket.close() } }
        }, "kodi-stop").apply { isDaemon = true }.start()
    }

    /** True once Kodi accepted the seek; false while no video player is active yet. */
    private fun seek(resumeMs: Long): Boolean {
        val players = call(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "Player.GetActivePlayers")).optJSONArray("result") ?: JSONArray()
        val video = (0 until players.length()).mapNotNull { players.optJSONObject(it) }.firstOrNull { it.optString("type") == "video" } ?: return false
        val seconds = resumeMs / 1000
        val time = JSONObject().put("hours", seconds / 3600).put("minutes", seconds / 60 % 60).put("seconds", seconds % 60).put("milliseconds", 0)
        val answer = call(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "Player.Seek")
            .put("params", JSONObject().put("playerid", video.optInt("playerid")).put("value", JSONObject().put("time", time))))
        return answer.has("result")
    }

    /**
     * One JSON-RPC request over Kodi's TCP interface on this device (port 9090, "Allow remote control from applications
     * on this system", on by default, no login). Kodi also sends notifications on the socket: the answer is the object
     * with this request's id.
     */
    private fun call(request: JSONObject): JSONObject {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(request.toString().toByteArray()); flush() }
            val input = socket.getInputStream().bufferedReader()
            val wanted = request.optInt("id")
            val current = StringBuilder()
            var depth = 0; var quoted = false; var escaped = false
            while (true) {
                val code = input.read()
                if (code < 0) throw java.io.EOFException("Kodi closed the connection")
                val ch = code.toChar()
                if (depth > 0 || ch == '{') current.append(ch)
                when {
                    escaped -> escaped = false
                    quoted && ch == '\\' -> escaped = true
                    ch == '"' -> quoted = !quoted
                    !quoted && ch == '{' -> depth++
                    !quoted && ch == '}' -> {
                        depth--
                        if (depth == 0) {
                            val answer = runCatching { JSONObject(current.toString()) }.getOrNull()
                            current.setLength(0)
                            if (answer != null && answer.optInt("id", -1) == wanted) return answer
                        }
                    }
                }
            }
        }
    }

    private fun tellOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(TOLD, false)) return
        prefs.edit().putBoolean(TOLD, true).apply()
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, context.getString(R.string.t_to_resume_in_kodi_turn_on), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
