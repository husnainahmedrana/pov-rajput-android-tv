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
    fun <T> searchTiers(tiers: List<List<T>>, backupTier: Boolean, quality: Int,
                        search: (T) -> List<SourceItem>): List<SourceItem> {
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
        val provider = AddonBadges.newsProvider(providers[0].name, providers[0].host).orEmpty()
        fun search(indexer: NewznabIndexer, identity: PlaybackIdentity): List<SourceItem> {
            // Out of today's searches (its own limit or the one the indexer reports): skipped like an indexer that is down.
            if (config.exhausted(indexer)) throw IllegalStateException(L10n.tr(R.string.t_x_used_today_s_searches, "%1\$s has used today's searches", indexer.name))
            config.noteSearch(indexer.id)
            if (EasynewsIndexer.isEasynews(indexer)) return EasynewsIndexer.search(indexer, identity, provider)
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

    /** The free-text search's categories: a Newznab category, or null for every category the indexer allows. */
    enum class NameCategory(val key: String, val newznab: String?) { ALL("all", null), MOVIES("movies", "2000"), TV("tv", "5000"), SPORTS("sports", "5060") }
    /** One indexer's part in a name search: how many links it gave, or why it gave none. */
    class NameAnswer(val name: String, val count: Int, val problem: String?)
    /**
     * A name search's links so far. [more] holds each source whose last page was full, with the next page to ask for
     * (a page number from 1); More results asks only those.
     */
    class NameResults(val items: List<SourceItem>, val answers: List<NameAnswer>, val more: Map<String, Int> = emptyMap())

    /** Name-search links play under this made-up id: no catalog title, so no tracker, history or Continue Watching. */
    const val NAME_ID = "nzb:"
    fun nameIdentity(query: String): PlaybackIdentity {
        val text = query.trim().take(200)
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.lowercase().toByteArray(Charsets.UTF_8))
        return PlaybackIdentity(NAME_ID + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }, "movie", text)
    }
    fun isNameSearch(identity: PlaybackIdentity?) = identity?.imdbId?.startsWith(NAME_ID) == true

    private fun activeProviders(store: SecureStore) = try { NewsProviders(store).active() } catch (_: Exception) { emptyList() }
    private fun enabledIndexers(store: SecureStore) = try { NewznabIndexers(store).load().filter { it.enabled } } catch (_: Exception) { emptyList() }
    private const val EASYNEWS_SOURCE = "easynews"
    private fun sourceKey(indexer: NewznabIndexer) = "indexer:${indexer.id}"

    /** Whether a name search has anywhere to look: indexers with a news provider to play them, or an Easynews account. */
    fun canSearchByName(store: SecureStore): Boolean =
        (activeProviders(store).isNotEmpty() && enabledIndexers(store).isNotEmpty()) || Easynews.active(store)

    /**
     * Searches every enabled indexer (and the Easynews account, when connected) at once for [query] by name: one page
     * each. Easynews has no categories (it only lists videos), so it answers in every category. The same release from
     * several indexers is shown once, from the first indexer in the viewer's order.
     */
    fun byName(store: SecureStore, query: String, category: NameCategory): NameResults =
        run(store, query, category, null)

    /** The next page from each source in [previous]'s [NameResults.more], added to its links (repeats left out). */
    fun more(store: SecureStore, query: String, category: NameCategory, previous: NameResults): NameResults {
        if (previous.more.isEmpty()) return previous
        val next = run(store, query, category, previous.more)
        val seen = previous.items.mapTo(HashSet(), ::sameRelease)
        val added = next.items.filter { seen.add(sameRelease(it)) }
        // Counts add up per source; a source asked again keeps its earlier count plus the new page.
        val counts = LinkedHashMap<String, NameAnswer>()
        previous.answers.forEach { counts[it.name] = it }
        next.answers.forEach { answer ->
            val before = counts[answer.name]
            counts[answer.name] = NameAnswer(answer.name, (before?.count ?: 0) + answer.count, answer.problem ?: before?.problem?.takeIf { answer.count == 0 })
        }
        return NameResults(previous.items + added, counts.values.toList(), next.more)
    }

    private fun sameRelease(item: SourceItem) = item.title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + "|" + ((item.sizeBytes ?: 0L) / 50_000_000L)

    /** One round: every source on its first page ([pages] null), or only the sources in [pages] on those pages. */
    private fun run(store: SecureStore, query: String, category: NameCategory, pages: Map<String, Int>?): NameResults {
        val text = query.trim().take(200)
        if (text.isEmpty()) return NameResults(emptyList(), emptyList())
        val providers = activeProviders(store)
        val config = NewznabIndexers(store)
        val indexers = (if (providers.isEmpty()) emptyList() else enabledIndexers(store))
            .filter { pages == null || sourceKey(it) in pages }
        val provider = providers.firstOrNull()?.let { AddonBadges.newsProvider(it.name, it.host) }.orEmpty()
        if (indexers.isNotEmpty() && pages == null) {
            providers[0].let { main -> try {
                dev.pov.tv.usenet.WarmConnections.warm(dev.pov.tv.usenet.NntpServer(main.host, main.port, main.tls, main.user, main.password), NewsProviders.warm { store.get(it) })
            } catch (_: Exception) {} }
            refreshHeaders(store)
        }
        val agent = UsenetHeaders.query({ store.get(it) })
        val client = Newznab()
        class Job(val key: String, val name: String, val page: Int, val work: () -> Pair<List<SourceItem>, Boolean>)
        val jobs = ArrayList<Job>()
        indexers.sortedWith(compareBy<NewznabIndexer> { it.backupOnly }.thenBy { it.priority }).forEach { indexer ->
            val key = sourceKey(indexer)
            val page = pages?.get(key) ?: 1
            jobs += Job(key, indexer.name, page) {
                if (config.exhausted(indexer)) throw IllegalStateException(L10n.tr(R.string.t_x_used_today_s_searches, "%1\$s has used today's searches", indexer.name))
                config.noteSearch(indexer.id)
                if (EasynewsIndexer.isEasynews(indexer)) EasynewsIndexer.searchText(indexer, text, provider, page)
                else client.searchTextPage(indexer, text, category.newznab, (page - 1) * Newznab.PAGE, agent, provider) { usage -> config.noteUsage(indexer.id, usage) }
            }
        }
        if (Easynews.active(store) && (pages == null || EASYNEWS_SOURCE in pages)) {
            val page = pages?.get(EASYNEWS_SOURCE) ?: 1
            jobs += Job(EASYNEWS_SOURCE, "Easynews", page) { EasynewsApi(store).searchTextPage(text, page) }
        }
        if (jobs.isEmpty()) return NameResults(emptyList(), emptyList())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(jobs.size.coerceIn(1, 8))
        val results = try {
            pool.invokeAll(jobs.map { job -> java.util.concurrent.Callable { runCatching { job.work() } } }, 30, java.util.concurrent.TimeUnit.SECONDS)
                .map { future -> try { if (future.isCancelled) Result.failure(Exception(L10n.tr(R.string.uns_no_answer, "no answer in time"))) else future.get() } catch (error: Exception) { Result.failure(error) } }
        } finally { pool.shutdownNow() }
        val answers = jobs.indices.map { index ->
            val result = results[index]
            NameAnswer(jobs[index].name, result.getOrNull()?.first?.size ?: 0, result.exceptionOrNull()?.let { it.message?.take(120) ?: it.javaClass.simpleName })
        }
        val more = LinkedHashMap<String, Int>()
        jobs.indices.forEach { index -> if (results[index].getOrNull()?.second == true) more[jobs[index].key] = jobs[index].page + 1 }
        val seen = HashSet<String>()
        val items = results.flatMap { it.getOrNull()?.first.orEmpty() }.filter { item -> seen.add(sameRelease(item)) }
        return NameResults(items, answers, more)
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
