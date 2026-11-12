package dev.pov.tv.usenet

/** One article of a file: its number in the file, the size the NZB states, and the message id to ask the server for. */
class NzbSegment(val number: Int, val bytes: Long, val messageId: String)

/** One posted file: its subject line and its articles in order. */
class NzbFile(val subject: String, val segments: List<NzbSegment>) {
    /** The size the NZB states. Usually the encoded size (a little over the real one); Easynews states the real one. */
    val statedBytes: Long get() = segments.sumOf { it.bytes }
}

class Nzb(val files: List<NzbFile>, val password: String?) {
    companion object {
        /**
         * Reads the few NZB fields a stream needs. NZB files are flat and regular, and a big video's runs to ten
         * megabytes, so this walks the text in place: an XML parser would try to fetch the DTD the file names, and
         * regular expressions copy the whole text (which ran a TV out of memory).
         */
        fun parse(text: String): Nzb {
            val files = ArrayList<NzbFile>()
            val firstFile = text.indexOf("<file")
            var at = firstFile
            while (at >= 0) {
                val tagEnd = text.indexOf('>', at)
                if (tagEnd < 0) break
                val close = text.indexOf("</file>", tagEnd).let { if (it < 0) text.length else it }
                val segments = ArrayList<NzbSegment>()
                var scan = tagEnd
                while (true) {
                    val open = text.indexOf("<segment", scan)
                    if (open < 0 || open >= close) break
                    scan = open + 8
                    if (scan >= text.length || !text[scan].isWhitespace()) continue      // "<segments>"
                    val end = text.indexOf('>', scan)
                    if (end < 0 || end >= close) break
                    val next = text.indexOf('<', end + 1)
                    if (next < 0 || next > close) break
                    scan = next
                    val number = attribute(text, open, end, "number")?.toIntOrNull() ?: continue
                    val id = unescape(text.substring(end + 1, next).trim()).removePrefix("<").removeSuffix(">")
                    // A message id goes on a command line: refuse anything that could break out of it.
                    if (id.isEmpty() || id.length > 250 || id.any { it <= ' ' || it == '<' || it == '>' || it.code > 126 }) continue
                    segments += NzbSegment(number, attribute(text, open, end, "bytes")?.toLongOrNull() ?: 0, id)
                }
                if (segments.isNotEmpty()) {
                    segments.sortBy { it.number }
                    val ordered = ArrayList<NzbSegment>(segments.size)
                    for (segment in segments) if (ordered.isEmpty() || ordered.last().number != segment.number) ordered += segment
                    files += NzbFile(unescape(attribute(text, at, tagEnd, "subject").orEmpty()), ordered)
                }
                at = text.indexOf("<file", close)
            }
            return Nzb(files, password(text, if (firstFile < 0) text.length else firstFile))
        }

        /** Read only bounded metadata; never log or persist archive passwords separately. */
        private fun password(text: String, limit: Int): String? {
            val header = text.take(minOf(limit, 64 * 1024))
            val meta = Regex("""<meta\b([^>]*)>(.*?)</meta\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (tag in meta.findAll(header)) {
                val type = Regex("""\btype\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
                    .find(tag.groupValues[1])?.groupValues?.get(2)?.lowercase()
                if (type !in setOf("password", "pass", "pwd")) continue
                val raw = tag.groupValues[2].trim()
                val value = if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) raw.substring(9, raw.length - 3)
                    else unescape(raw)
                if (value.isNotEmpty() && value.length <= 512) return value
            }
            return null
        }

        private fun attribute(text: String, from: Int, to: Int, name: String): String? {
            val key = " $name=\""
            val at = text.indexOf(key, from)
            if (at < 0 || at >= to) return null
            val start = at + key.length
            val end = text.indexOf('"', start)
            return if (end < 0 || end > to) null else text.substring(start, end)
        }

        private val entities = Regex("&(?:lt|gt|quot|apos|amp|#[0-9]+|#x[0-9a-fA-F]+);")
        private fun unescape(value: String): String = if ('&' !in value) value else entities.replace(value) { match ->
            when (match.value) {
                "&lt;" -> "<"
                "&gt;" -> ">"
                "&quot;" -> "\""
                "&apos;" -> "'"
                "&amp;" -> "&"
                else -> {
                    val number = match.value.substring(2, match.value.length - 1)
                    val code = if (number.startsWith("x")) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
                        String(Character.toChars(code)) else match.value
                }
            }
        }
    }
}
