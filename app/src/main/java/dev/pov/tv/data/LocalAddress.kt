package dev.pov.tv.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * Hosts on the viewer's own network, where a self-hosted add-on often has no HTTPS certificate. Only names that can
 * never point to the public internet count: IP literals in private ranges, localhost and .local (mDNS) names.
 * Ordinary domain names never count, even when they resolve to a private IP, so plain HTTP can't leak to a public server.
 */
object LocalAddress {
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun isLocal(host: String?): Boolean {
        val clean = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')?.lowercase(Locale.ROOT)
        if (clean.isNullOrEmpty()) return false
        if (clean == "localhost" || clean.endsWith(".localhost") || clean.endsWith(".local")) return true
        // Parse IP literals only; a name here would mean a DNS lookup.
        val ip = when {
            IPV4.matches(clean) -> if (clean.split('.').all { it.toInt() <= 255 }) InetAddress.getByName(clean) else return false
            ':' in clean -> runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return false
            else -> return false
        }
        if (ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress) return true
        return when (ip) {
            // 100.64.0.0/10: carrier-grade NAT, also Tailscale addresses.
            is Inet4Address -> ip.address[0].toInt() and 0xFF == 100 && (ip.address[1].toInt() and 0xC0) == 64
            // fc00::/7: unique local addresses.
            is Inet6Address -> ip.address[0].toInt() and 0xFE == 0xFC
            else -> false
        }
    }
}
