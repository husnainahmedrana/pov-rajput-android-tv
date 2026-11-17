package dev.pov.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DebridPriorityTest {
    @Test fun `existing selected service becomes first priority`() {
        assertEquals(listOf("offcloud", "torbox") + LinkDebrids.IDS, DebridServices.parsePriority("", "offcloud"))
        assertEquals(listOf("torbox", "offcloud") + LinkDebrids.IDS, DebridServices.parsePriority("", "torbox"))
    }
    @Test fun `saved priority takes precedence over legacy selection`() {
        assertEquals(listOf("offcloud", "torbox") + LinkDebrids.IDS, DebridServices.parsePriority("offcloud,torbox", "torbox"))
        assertEquals(listOf("torbox", "offcloud") + LinkDebrids.IDS, DebridServices.parsePriority("torbox,offcloud", "offcloud"))
    }
    @Test fun `invalid duplicate and missing entries cannot disable a connected service`() {
        assertEquals(listOf("torbox", "offcloud") + LinkDebrids.IDS, DebridServices.parsePriority("unknown,torbox,torbox", "offcloud"))
        assertEquals(listOf("offcloud", "torbox") + LinkDebrids.IDS, DebridServices.parsePriority("unknown", "offcloud"))
    }
    @Test fun `five providers retain saved ranks and append new integrations`() {
        val providers = listOf("offcloud", "torbox", "debridlink", "realdebrid", "alldebrid")
        assertEquals(listOf("torbox", "offcloud", "debridlink", "realdebrid", "alldebrid"),
            DebridServices.parsePriority("torbox,offcloud", "offcloud", providers))
        assertEquals(listOf("alldebrid", "torbox", "offcloud", "debridlink", "realdebrid"),
            DebridServices.parsePriority("alldebrid,torbox,unknown,torbox", "offcloud", providers))
    }
    @Test fun `check-on-play services above the cached one get the first try`() {
        val services = listOf("realdebrid", "torbox", "offcloud")
        assertEquals(listOf("realdebrid", "torbox"), DebridServices.playOrder(services, listOf("torbox")))
        assertEquals(listOf("realdebrid", "torbox", "offcloud"), DebridServices.playOrder(services, listOf("torbox", "offcloud")))
        assertEquals(listOf("realdebrid", "offcloud"), DebridServices.playOrder(services, listOf("offcloud")))
    }
    @Test fun `check-on-play services below the cached one are not tried`() {
        assertEquals(listOf("torbox"), DebridServices.playOrder(listOf("torbox", "realdebrid", "offcloud"), listOf("torbox")))
        assertEquals(listOf("torbox", "offcloud"), DebridServices.playOrder(listOf("torbox", "realdebrid", "offcloud"), listOf("torbox", "offcloud")))
    }
    @Test fun `play order is empty when nothing is cached and honours skip`() {
        assertEquals(emptyList<String>(), DebridServices.playOrder(listOf("realdebrid", "torbox"), emptyList()))
        assertEquals(listOf("alldebrid", "torbox"), DebridServices.playOrder(listOf("realdebrid", "alldebrid", "torbox"), listOf("torbox")) { it == "realdebrid" })
    }
    @Test fun `moving among five providers preserves all other ranks`() {
        val order = listOf("offcloud", "torbox", "debridlink", "realdebrid", "alldebrid")
        val moved = DebridServices.moved(order, 3, -1)
        assertEquals(listOf("offcloud", "torbox", "realdebrid", "debridlink", "alldebrid"), moved)
        assertEquals(order, DebridServices.moved(moved, 2, 1))
        assertEquals(order, DebridServices.moved(order, 0, -1))
        assertEquals(order, DebridServices.moved(order, 4, 1))
    }
}
