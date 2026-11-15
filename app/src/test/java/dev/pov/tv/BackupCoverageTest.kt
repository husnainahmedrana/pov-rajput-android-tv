package dev.pov.tv

import dev.pov.tv.data.BackupSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCoverageTest {
    @Test fun `Usenet providers, indexers, add-ons and Easynews are backed up (with the secrets)`() {
        for (key in listOf("usenet.providers", "usenet.indexers", "sources.providers.v1", "easynews.username", "easynews.password", "realdebrid.key")) {
            assertFalse("$key must not be excluded", BackupSnapshot.excluded(key))
            assertFalse("$key holds logins, so it stays with the secrets", BackupSnapshot.isSetting(key))
        }
    }

    @Test fun `on-off switches, debrid priority and Usenet choices travel as settings`() {
        for (key in listOf("debrid.off.realdebrid", "easynews.off", "playback.priority", "usenet.warm", "usenet.nextTierQuality",
                "usenet.fallbackOnly", "easynews.nntp.pipeline", "easynews.nntp.connections"))
            assertTrue("$key should be a setting", BackupSnapshot.isSetting(key))
    }

    @Test fun `daily indexer counters stay on the device`() {
        for (key in listOf("usenet.searches.abc", "usenet.grabs.abc", "usenet.usage.abc")) assertTrue(BackupSnapshot.excluded(key))
    }

    @Test fun `language, screen fit and remembered release groups are in the settings files`() {
        for (file in listOf("pov_language", "pov_player_screen", "pov_last_group")) assertTrue(file in BackupSnapshot.SETTINGS_FILES)
    }
}
