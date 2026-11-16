package dev.pov.tv.data

/**
 * Real-Debrid's copyright filter (since May 2026): it answers `451 {"error":"infringing_file","error_code":35}`
 * for releases whose NAME contains certain strings, on `torrents/addMagnet` (the torrent's root name) and on
 * `unrestrict/link` (each file's own name). Such torrents still look cached ("downloaded"), so they used to eat
 * the check-on-play tries and then fail to play.
 *
 * Patterns as measured by Debrid Media Manager on 2026-10-14 (src/utils/deInfringe.ts): `WEB-DL`, `WEB.x264`
 * and `WEB.H264` only in exactly that case; `WEBRip`, `BDRip`, `HDRip`, `DVDRip`, `BluRay.x264`, `p.BluRay.DTS`,
 * `HDTV.x264`, `HDTV.XviD` in any case. RD has changed the list several times, so the play path also treats a
 * real 451 as "skip this link" ([refusal]) and does not rely on these names alone.
 */
object RealDebridFilter {
    const val SERVICE = "realdebrid"
    private val EXACT_CASE = Regex("WEB-DL|WEB\\.(?:x264|H264)")
    private val ANY_CASE = Regex("(?:web|bd|hd|dvd)rip|bluray\\.x264|p\\.bluray\\.dts|hdtv\\.(?:x264|xvid)", RegexOption.IGNORE_CASE)

    fun refusedName(name: String) = EXACT_CASE.containsMatchIn(name) || ANY_CASE.containsMatchIn(name)

    /** The link's file name or its torrent's own name (what Real-Debrid judges on add) is one it refuses. */
    fun refused(source: SourceItem) = refusedName(source.title) || refusedName(source.torrentName)

    /** The 451 / `infringing_file` answer, from the status or (behind a proxy) the error name. */
    fun refusal(error: Throwable) = error is ApiException && (error.code == 451 || error.errorKey == "infringing_file")

    /**
     * Whether the link list should leave this link out because Real-Debrid would refuse it: an add-on link served
     * by Real-Debrid, or a torrent only Real-Debrid could play (no other connected debrid service, not cached elsewhere).
     */
    /** [sources] without the links [hides] leaves out: the results page, auto-play and the player's Streams panel all use it. */
    fun visible(sources: List<SourceItem>, connected: List<String>) = sources.filterNot { hides(it, connected) }

    fun hides(source: SourceItem, connected: List<String>): Boolean {
        if (!refused(source)) return false
        if (source.servedBy == SERVICE) return true
        if (source.directUrl.isNotEmpty() || source.infoHash.isBlank()) return false
        if (source.cachedServices.any { it != SERVICE }) return false
        return connected.isNotEmpty() && connected.all { it == SERVICE }
    }
}
