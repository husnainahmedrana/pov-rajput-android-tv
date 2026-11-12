package dev.pov.tv.usenet

/** Briefly reuse only conclusive successful probes. Keys include the release and provider configuration. */
internal class HealthCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val ttlMs: Long = 120_000) {
    private val successes = LinkedHashMap<String, Long>()
    @Synchronized fun contains(key: String): Boolean {
        val saved = successes[key] ?: return false
        if (now() - saved !in 0 until ttlMs) { successes.remove(key); return false }
        return true
    }
    @Synchronized fun remember(key: String) {
        successes[key] = now()
        while (successes.size > 64) successes.remove(successes.keys.first())
    }
    @Synchronized fun clear() { successes.clear() }
}
