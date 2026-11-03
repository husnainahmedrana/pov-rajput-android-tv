package dev.pov.tv.data

import java.net.URI
import java.util.Locale

/**
 * Add-ons with their own logo (PenguPlay, CNCVerse). They are known by the add-on's address, which stays the same
 * however the viewer names the add-on; the name is only a fallback, e.g. for a self-hosted copy.
 */
object AddonBadges {
    const val PENGUPLAY = "penguplay"
    const val CNCVERSE = "cncverse"
    val IDS = setOf(PENGUPLAY, CNCVERSE)

    private val HOSTS = mapOf("pengu.uk" to PENGUPLAY, "cncverse.dpdns.org" to CNCVERSE)

    fun of(address: String, name: String = ""): String? {
        val host = try { URI(address.trim()).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }.orEmpty()
        HOSTS.entries.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.let { return it.value }
        return when {
            name.contains("pengu", ignoreCase = true) -> PENGUPLAY
            name.contains("cncverse", ignoreCase = true) -> CNCVERSE
            else -> null
        }
    }
}
