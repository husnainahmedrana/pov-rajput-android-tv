package dev.pov.tv.data

/** The viewer's indexers as searches for the link list. Without a news provider their links couldn't play, so none are offered. */
object UsenetSearch {
    /** "true": indexers are searched only when the add-ons (and Easynews) found no links at all. Off by default. */
    const val FALLBACK_ONLY = "usenet.fallbackOnly"
    fun fallbackOnly(store: SecureStore) = store.get(FALLBACK_ONLY) == "true"

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
        return indexers.map { indexer ->
            SourceResolver.IndexerSearch("indexer:${indexer.id}", indexer.name, fallback = fallbackOnly(store)) { identity ->
                client.search(indexer, identity, EasynewsApi.cinemetaYear(identity), agent, provider) { usage -> config.noteUsage(indexer.id, usage) }
            }
        }
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
