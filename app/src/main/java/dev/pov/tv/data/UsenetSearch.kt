package dev.pov.tv.data

import dev.pov.tv.L10n
import dev.pov.tv.R

/** The viewer's indexers as searches for the link list. Without a news provider their links couldn't play, so none are offered. */
object UsenetSearch {
    /** "true": indexers are searched only when the add-ons (and Easynews) found no links at all. Off by default. */
    const val FALLBACK_ONLY = "usenet.fallbackOnly"
    fun fallbackOnly(store: SecureStore) = store.get(FALLBACK_ONLY) == "true"

    /**
     * With indexer priorities, the next priority is searched only when the links so far have nothing at this height
     * (2160, 1080 or 720), or with 0 only when they found nothing at all. 1080 by default.
     */
    const val NEXT_TIER_QUALITY = "usenet.nextTierQuality"
    val NEXT_TIER_CHOICES = listOf(2160, 1080, 720, 0)
    fun nextTierQuality(store: SecureStore) = store.get(NEXT_TIER_QUALITY).toIntOrNull()?.takeIf { it in NEXT_TIER_CHOICES } ?: 1080

    /** Whether [found] is good enough to stop before the next priority ([quality] as in [nextTierQuality]). */
    fun enough(found: List<SourceItem>, quality: Int): Boolean =
        if (quality == 0) found.isNotEmpty() else found.any { (it.qualityHeight ?: 0) >= quality }

    /** The indexers' search order: priority groups in turn, backup-only indexers last. */
    fun tiers(indexers: List<NewznabIndexer>): List<List<NewznabIndexer>> =
        indexers.groupBy { if (it.backupOnly) Int.MAX_VALUE else it.priority }.toSortedMap().values.toList()

    /**
     * Searches [tiers] in order: one priority's indexers together, then the next only while [enough] says the links so
     * far fall short; backup-only indexers only when nothing at all was found. A tier whose every indexer failed counts
     * as finding nothing; when every indexer searched failed, the last failure is reported.
     */
    fun searchTiers(tiers: List<List<NewznabIndexer>>, backupTier: Boolean, quality: Int,
                    search: (NewznabIndexer) -> List<SourceItem>): List<SourceItem> {
        val found = mutableListOf<SourceItem>()
        var failure: Exception? = null
        var answered = false
        tiers.forEachIndexed { index, tier ->
            val backup = backupTier && index == tiers.lastIndex
            if (index > 0 && (if (backup) found.isNotEmpty() else enough(found, quality))) return found
            val pool = java.util.concurrent.Executors.newFixedThreadPool(tier.size.coerceIn(1, 8))
            try {
                pool.invokeAll(tier.map { indexer -> java.util.concurrent.Callable { runCatching { search(indexer) } } }).forEach { future ->
                    future.get().onSuccess { answered = true; found += it }.onFailure { failure = it as? Exception ?: Exception(it) }
                }
            } finally { pool.shutdownNow() }
        }
        if (!answered) failure?.let { throw it }
        return found
    }

    fun sources(store: SecureStore): List<SourceResolver.IndexerSearch> {
        val providers = try { NewsProviders(store).active() } catch (_: Exception) { emptyList() }
        if (providers.isEmpty()) return emptyList()
        val config = NewznabIndexers(store)
        val indexers = try { config.load().filter { it.enabled } } catch (_: Exception) { emptyList() }
        if (indexers.isEmpty()) return emptyList()
        // Usenet links are on their way: sign in to the main provider now, so pressing play skips that wait.
        providers[0].let { main -> try {
            dev.pov.tv.usenet.WarmConnections.warm(dev.pov.tv.usenet.NntpServer(main.host, main.port, main.tls, main.user, main.password), NewsProviders.warm { store.get(it) })
        } catch (_: Exception) {} }
        refreshHeaders(store)
        val agent = UsenetHeaders.query({ store.get(it) })
        val client = Newznab()
        // The main provider's logo id, when it is one the app has a logo for.
        val provider = AddonBadges.newsProvider("${providers[0].name} ${providers[0].host}").orEmpty()
        fun search(indexer: NewznabIndexer, identity: PlaybackIdentity): List<SourceItem> {
            // Out of today's searches (its own limit or the one the indexer reports): skipped like an indexer that is down.
            if (config.exhausted(indexer)) throw IllegalStateException(L10n.tr(R.string.t_x_used_today_s_searches, "%1\$s has used today's searches", indexer.name))
            config.noteSearch(indexer.id)
            return client.search(indexer, identity, EasynewsApi.cinemetaYear(identity), agent, provider) { usage -> config.noteUsage(indexer.id, usage) }
        }
        val tiers = tiers(indexers)
        // One priority and no backups: every indexer is its own search, as before.
        if (tiers.size <= 1) return indexers.map { indexer ->
            SourceResolver.IndexerSearch("indexer:${indexer.id}", indexer.name, fallback = fallbackOnly(store)) { identity -> search(indexer, identity) }
        }
        val quality = nextTierQuality(store)
        val backupTier = indexers.any { it.backupOnly }
        return listOf(SourceResolver.IndexerSearch("indexers", L10n.tr(R.string.t_usenet_indexers, "Usenet indexers"), fallback = fallbackOnly(store)) { identity ->
            searchTiers(tiers, backupTier, quality) { indexer -> search(indexer, identity) }
        })
    }

    @Volatile private var refreshing = false

    /** About once a day, in the background, move the indexer headers to their tools' current versions. */
    private fun refreshHeaders(store: SecureStore) {
        if (refreshing || !UsenetHeaders.due({ store.get(it) })) return
        refreshing = true
        Thread({
            try { UsenetHeaders.refresh({ store.get(it) }, { key, value -> store.put(key, value) }) } catch (_: Exception) {
                // Try again tomorrow rather than on every search.
                store.put(UsenetHeaders.CHECKED, System.currentTimeMillis().toString())
            } finally { refreshing = false }
        }, "usenet-headers").apply { isDaemon = true }.start()
    }
}
