package dev.pov.tv.data

import java.security.MessageDigest

/**
 * How the viewer's indexer links play (Settings → Sources → Usenet → Play Usenet links with): from their own news
 * providers (built in), through TorBox Pro, or through Deepbrid. One at a time.
 */
object UsenetRoute {
    const val KEY = "usenet.via"
    const val NEWS = ""
    const val TORBOX = "torbox"
    const val DEEPBRID = "deepbrid"
    val CHOICES = listOf(NEWS, TORBOX, DEEPBRID)
    fun get(store: SecureStore): String = store.get(KEY).takeIf { it in CHOICES } ?: NEWS
    fun set(store: SecureStore, value: String) { require(value in CHOICES); store.put(KEY, value) }
}

/**
 * TorBox Usenet (experimental, TorBox Pro): Usenet links from the viewer's indexers play from TorBox's servers instead
 * of the viewer's news providers. On play, Nabrio fetches the NZB itself (the indexer sees this device and its
 * headers, as for built-in streaming) and asks TorBox whether it has the release; a cached one plays at once, any other
 * is uploaded as a file (never the indexer link, so the API key inside it stays here) and the player waits while
 * TorBox fetches it.
 *
 * TorBox matches an NZB by MD5 of each file's first message ID (its preferred way, which survives indexer-specific
 * formatting and works across accounts), of the NZB itself (raw and cleaned) and of the link.
 */
object TorBoxUsenet {
    fun enabled(store: SecureStore): Boolean = UsenetRoute.get(store) == UsenetRoute.TORBOX

    /** The hashes TorBox may know this NZB by, the strongest first (message IDs first file first, NZB, cleaned NZB, link). */
    fun hashes(nzb: ByteArray, messageIds: List<String>, link: String?): List<String> {
        val out = LinkedHashSet<String>()
        messageIds.firstOrNull()?.let { out += md5(it.toByteArray(Charsets.UTF_8)) }
        val cleaned = clean(nzb)
        if (cleaned.isNotEmpty()) out += md5(cleaned)
        out += md5(nzb)
        link?.takeIf { it.isNotBlank() }?.let { out += md5(it.toByteArray(Charsets.UTF_8)) }
        messageIds.drop(1).take(90).forEach { out += md5(it.toByteArray(Charsets.UTF_8)) }
        return out.toList()
    }

    /**
     * TorBox's documented NZB cleanup, as other TorBox clients do it: no byte-order mark, no comments, no poster
     * attributes, trimmed. Plain string scanning: an NZB can be 10–20 MB, too big for regular expressions on a TV.
     */
    internal fun clean(nzb: ByteArray): ByteArray {
        var text = String(nzb, Charsets.UTF_8).removePrefix(Char(0xFEFF).toString())
        val builder = StringBuilder(text.length)
        var at = 0
        while (true) {
            val open = text.indexOf("<!--", at)
            if (open < 0) { builder.append(text, at, text.length); break }
            builder.append(text, at, open)
            val close = text.indexOf("-->", open + 4)
            if (close < 0) break
            at = close + 3
        }
        text = builder.toString()
        builder.setLength(0)
        // Each ` poster="…"` (or '…'), with the whitespace before it.
        at = 0
        var from = 0
        while (true) {
            val name = text.indexOf("poster", from, ignoreCase = true)
            if (name < 0) break
            from = name + 6
            var space = name
            while (space > 0 && text[space - 1].isWhitespace()) space--
            if (space == name) continue
            var i = name + 6
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '=') continue
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || (text[i] != '"' && text[i] != '\'')) continue
            val close = text.indexOf(text[i], i + 1)
            if (close < 0) break
            builder.append(text, at, space)
            at = close + 1; from = at
        }
        builder.append(text, at, text.length)
        return builder.toString().trim().toByteArray(Charsets.UTF_8)
    }

    /** The video to play from a finished TorBox download: the biggest video file, else the biggest file. */
    fun pickFile(files: List<CloudFile>): CloudFile? {
        val videos = files.filter { VIDEO.containsMatchIn(it.name) && !SAMPLE.containsMatchIn(it.name) }
        return (videos.ifEmpty { files }).maxByOrNull { it.size }
    }

    private val VIDEO = Regex("""\.(mkv|mp4|m4v|avi|mov|ts|m2ts|webm|wmv)$""", RegexOption.IGNORE_CASE)
    private val SAMPLE = Regex("""(^|[ ._-])sample([ ._-]|$)""", RegexOption.IGNORE_CASE)

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
