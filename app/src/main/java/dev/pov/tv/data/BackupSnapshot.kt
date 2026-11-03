package dev.pov.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an account backup holds, in two layers.
 *  - Settings: Home rows, appearance, playback, subtitles, audio, source sorting, skip buttons, player and preview
 *    choices, and local watch progress. A password reset by email alone brings these back.
 *  - Secrets: everything else in the encrypted account store — debrid services, add-on addresses, TMDB, Easynews,
 *    Seekr and Wyzie keys, WeTrakr and Simkl logins. Only the password or the recovery key opens these.
 * Caches, the tracker outboxes and the account's own keys are never included.
 */
object BackupSnapshot {
    const val VERSION = 1

    /** Plain preference files that belong in the settings layer. */
    val SETTINGS_FILES = listOf("pov_home_rows", "pov_appearance", "pov_playback", "pov_subtitles", "pov_audio",
        "pov_sources", "pov_skip", "pov_player", "pov_seek_previews", "pov_video", PLAYBACK_FILE)
    private const val PLAYBACK_FILE = "playback"
    /** From the playback file only local progress and hidden Continue Watching items; tracker outboxes stay per device. */
    private val PLAYBACK_KEYS = setOf("recent", "hiddenContinue")

    /** Encrypted-store keys that are preferences rather than credentials. Anything not listed is treated as secret. */
    private val SETTING_PREFIXES = listOf("settings.", "home.", "home_rows.", "sources.autoplay", "sources.hide_uncached",
        "tmdb.region", "theme", "tracker.service", "tracking.", "updates.", "playback.priority", "playback.service",
        "easynews.connections", "easynews.server", "easynews.off")
    private val EXCLUDED_PREFIXES = listOf("account.", "wetrakr.continue_cache", "playback.last_links", "offcloud.transfers")

    fun excluded(key: String) = EXCLUDED_PREFIXES.any(key::startsWith)
    fun isSetting(key: String) = !excluded(key) && SETTING_PREFIXES.any(key::startsWith)

    class Layers(val settings: JSONObject, val secrets: JSONObject) {
        /** Identifies the content, so an unchanged setup is not uploaded again. */
        fun fingerprint(): String = BackupCrypto.encode(BackupCrypto.sha256((settings.toString() + "\u0000" + secrets.toString()).toByteArray(Charsets.UTF_8)))
        /** True when the device has nothing worth keeping yet, e.g. a fresh install. */
        fun empty(): Boolean = secrets.optJSONObject("store")?.length().let { it == null || it == 0 } &&
            settings.optJSONObject("prefs")?.let { prefs -> prefs.keys().asSequence().all { prefs.optJSONObject(it)?.length() == 0 } } != false
    }

    fun collect(context: Context, entries: Map<String, String>): Layers {
        val settingsStore = JSONObject(); val secretsStore = JSONObject()
        entries.keys.sorted().forEach { key ->
            if (excluded(key)) return@forEach
            val value = entries.getValue(key)
            if (isSetting(key)) settingsStore.put(key, value) else secretsStore.put(key, value)
        }
        val prefs = JSONObject()
        SETTINGS_FILES.forEach { file ->
            val values = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).all
            val out = JSONObject()
            values.keys.sorted().forEach { key ->
                if (file == PLAYBACK_FILE && key !in PLAYBACK_KEYS) return@forEach
                typed(values[key])?.let { out.put(key, it) }
            }
            prefs.put(file, out)
        }
        return Layers(JSONObject().put("v", VERSION).put("prefs", prefs).put("store", settingsStore),
            JSONObject().put("v", VERSION).put("store", secretsStore))
    }

    /** Writes a restored layer over this device's values. Keys the backup does not have are left as they are. */
    fun apply(context: Context, store: CloudAccount.Store, layer: JSONObject?) {
        if (layer == null) return
        require(layer.optInt("v") in 1..VERSION) { "This backup was made by a newer version of POV. Update the app first." }
        layer.optJSONObject("store")?.let { saved ->
            val values = saved.keys().asSequence().filterNot(::excluded).associateWith { saved.getString(it) }
            if (values.isNotEmpty()) store.putAll(values)
        }
        layer.optJSONObject("prefs")?.let { files ->
            files.keys().asSequence().filter { it in SETTINGS_FILES }.forEach { file ->
                val saved = files.getJSONObject(file)
                val editor = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                saved.keys().forEach { key ->
                    if (file == PLAYBACK_FILE && key !in PLAYBACK_KEYS) return@forEach
                    val item = saved.getJSONObject(key); val value = item.get("v")
                    when (item.getString("t")) {
                        "s" -> editor.putString(key, value as String)
                        "b" -> editor.putBoolean(key, value as Boolean)
                        "i" -> editor.putInt(key, (value as Number).toInt())
                        "l" -> editor.putLong(key, (value as Number).toLong())
                        "f" -> editor.putFloat(key, (value as Number).toFloat())
                        "set" -> editor.putStringSet(key, (value as JSONArray).let { array -> (0 until array.length()).map(array::getString).toSet() })
                    }
                }
                check(editor.commit()) { "Restored settings could not be saved. Please check device storage." }
            }
        }
    }

    private fun typed(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>().sorted()))
        else -> null
    }
}
