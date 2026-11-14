package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class UsenetTiersTest {
    private fun indexer(name: String, priority: Int = 1, backup: Boolean = false) =
        NewznabIndexer(name, name, "https://$name.example/api", "key", priority = priority, backupOnly = backup)
    private fun link(name: String, height: Int?) = SourceItem(title = name, provider = "p", directUrl = "https://x.example/$name", qualityHeight = height)

    @Test fun tiersFollowPriorityWithBackupsLast() {
        val tiers = UsenetSearch.tiers(listOf(indexer("free", backup = true), indexer("b", 2), indexer("a", 1), indexer("c", 1)))
        assertEquals(listOf(listOf("a", "c"), listOf("b"), listOf("free")), tiers.map { tier -> tier.map { it.name } })
    }

    @Test fun nextPriorityOnlyWhenTheLinksFallShort() {
        val tiers = UsenetSearch.tiers(listOf(indexer("paid", 1), indexer("second", 2), indexer("free", backup = true)))
        val asked = Collections.synchronizedList(mutableListOf<String>())
        // Priority 1 found 1080p: the rest are never asked.
        val found = UsenetSearch.searchTiers(tiers, backupTier = true, quality = 1080) { asked += it.name; listOf(link(it.name, 1080)) }
        assertEquals(listOf("paid"), asked)
        assertEquals(1, found.size)

        // Only 720p at priority 1: priority 2 is asked; it finds 1080p, so the free tier is still skipped.
        asked.clear()
        UsenetSearch.searchTiers(tiers, backupTier = true, quality = 1080) { asked += it.name; listOf(link(it.name, if (it.name == "paid") 720 else 1080)) }
        assertEquals(listOf("paid", "second"), asked)

        // Something found but below 1080p: backups still wait (they run only when nothing at all was found).
        asked.clear()
        UsenetSearch.searchTiers(tiers, backupTier = true, quality = 1080) { asked += it.name; listOf(link(it.name, 480)) }
        assertEquals(listOf("paid", "second"), asked)
    }

    @Test fun backupsWhenEveryOtherIndexerFails() {
        val tiers = UsenetSearch.tiers(listOf(indexer("paid", 1), indexer("free", backup = true)))
        val found = UsenetSearch.searchTiers(tiers, backupTier = true, quality = 1080) {
            if (it.name == "paid") throw IllegalStateException("limit used up") else listOf(link("free", 1080))
        }
        assertEquals(listOf("free"), found.map { it.title })
    }

    @Test fun anyResultsStopsAtTheFirstAnswer() {
        val tiers = UsenetSearch.tiers(listOf(indexer("paid", 1), indexer("second", 2)))
        val asked = mutableListOf<String>()
        UsenetSearch.searchTiers(tiers, backupTier = false, quality = 0) { asked += it.name; listOf(link(it.name, 480)) }
        assertEquals(listOf("paid"), asked)
    }

    @Test fun everyFailureIsReported() {
        val tiers = UsenetSearch.tiers(listOf(indexer("paid", 1), indexer("second", 2)))
        val error = runCatching { UsenetSearch.searchTiers(tiers, backupTier = false, quality = 1080) { throw IllegalStateException("down") } }.exceptionOrNull()
        assertTrue(error?.message == "down")
    }

    @Test fun dailyLimitSkipsAnIndexerUntilTomorrow() {
        val saved = HashMap<String, String>()
        val config = NewznabIndexers({ saved[it].orEmpty() }, { k, v -> saved[k] = v })
        val geek = indexer("geek").copy(dailyLimit = 2)
        assertTrue(!config.exhausted(geek))
        config.noteSearch("geek"); config.noteSearch("geek")
        assertEquals(2, config.searchesToday("geek"))
        assertTrue(config.exhausted(geek))
        assertTrue(!config.exhausted(geek.copy(dailyLimit = 0)))
        // Yesterday's count no longer counts.
        saved["usenet.searches.geek"] = "2000-01-01:50"
        assertEquals(0, config.searchesToday("geek"))
        assertTrue(!config.exhausted(geek))
    }

    @Test fun anIndexerThatSaysItsAllowanceIsUsedUpIsSkipped() {
        val saved = HashMap<String, String>()
        val config = NewznabIndexers({ saved[it].orEmpty() }, { k, v -> saved[k] = v })
        config.noteUsage("free", "API 25/25 · grabs 3/10")
        assertTrue(config.exhausted(indexer("free")))
        config.noteUsage("free", "API 24/25")
        assertTrue(!config.exhausted(indexer("free")))
    }

    @Test fun grabLimitLeavesTheIndexerOutOfSearches() {
        val saved = HashMap<String, String>()
        val config = NewznabIndexers({ saved[it].orEmpty() }, { k, v -> saved[k] = v })
        val free = indexer("free").copy(dailyLimit = 100, dailyGrabLimit = 1)
        assertTrue(!config.exhausted(free))
        config.noteGrab("free")
        assertEquals(1, config.grabsToday("free"))
        assertTrue(config.grabsExhausted(free))
        assertTrue(config.exhausted(free))
        // The indexer's own count says grabs are used up, even with no limit set here.
        config.noteUsage("other", "API 3/100 · grabs 5/5")
        assertTrue(config.exhausted(indexer("other")))
    }
}
