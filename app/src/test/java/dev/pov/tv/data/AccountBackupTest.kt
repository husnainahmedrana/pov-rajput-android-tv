package dev.pov.tv.data

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountBackupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    class MapStore(val data: MutableMap<String, String> = mutableMapOf()) : CloudAccount.Store {
        override fun get(key: String) = data[key].orEmpty()
        override fun putAll(values: Map<String, String>) { data.putAll(values) }
        override fun remove(key: String) { data.remove(key) }
        override fun entries() = data.toSortedMap()
    }

    @Test fun `sealed data opens only with the same key and label`() {
        val key = BackupCrypto.newKey()
        val sealed = BackupCrypto.seal(key, "hello".toByteArray(), "label-a")
        assertEquals("hello", String(BackupCrypto.open(key, sealed, "label-a")))
        assertThrows(Exception::class.java) { BackupCrypto.open(BackupCrypto.newKey(), sealed, "label-a") }
        assertThrows(Exception::class.java) { BackupCrypto.open(key, sealed, "label-b") }
        assertNotEquals(sealed, BackupCrypto.seal(key, "hello".toByteArray(), "label-a"))
    }

    @Test fun `login values depend on email and password, never on email case`() {
        val a = BackupCrypto.login(" Someone@Example.com ", "correct horse", iterations = 1_000)
        val b = BackupCrypto.login("someone@example.com", "correct horse", iterations = 1_000)
        val c = BackupCrypto.login("someone@example.com", "correct horsE", iterations = 1_000)
        assertEquals(a.auth, b.auth); assertArrayEquals(a.passwordKey, b.passwordKey)
        assertNotEquals(a.auth, c.auth)
        // The login token sent to the server is not the key that unlocks the data.
        assertNotEquals(a.auth, BackupCrypto.encode(a.passwordKey))
    }

    @Test fun `hkdf matches RFC 5869 test case 3`() {
        val okm = BackupCrypto.hkdf(ByteArray(22) { 0x0b }, "", 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.joinToString("") { "%02x".format(it) })
    }

    @Test fun `recovery keys are five groups of five and forgive case and look-alike letters`() {
        val key = BackupCrypto.newRecoveryKey()
        assertTrue(Regex("^([A-Z0-9]{5}-){4}[A-Z0-9]{5}$").matches(key))
        assertTrue(BackupCrypto.validRecoveryKey(key.lowercase().replace("-", " ")))
        assertArrayEquals(BackupCrypto.recoveryKeyBytes("ABCDE-0123A-11111-22222-33333"), BackupCrypto.recoveryKeyBytes("abcde o123a ilili 22222 33333"))
        assertFalse(BackupCrypto.validRecoveryKey("ABCDE-12345"))
    }

    @Test fun `credentials go in the secrets layer, preferences in settings, caches and account keys nowhere`() {
        assertFalse(BackupSnapshot.isSetting("tmdb.key")); assertTrue(BackupSnapshot.isSetting("tmdb.region"))
        assertFalse(BackupSnapshot.isSetting("sources.providers.v1")); assertFalse(BackupSnapshot.isSetting("catalogs.addons.v1"))
        assertFalse(BackupSnapshot.isSetting("wetrakr.access_token")); assertFalse(BackupSnapshot.isSetting("easynews.password"))
        assertTrue(BackupSnapshot.isSetting("home.resume_cards")); assertTrue(BackupSnapshot.isSetting("easynews.connections"))
        listOf("account.auth", "account.data_key", "wetrakr.continue_cache", "playback.last_links").forEach { assertTrue(it, BackupSnapshot.excluded(it)) }
        val layers = BackupSnapshot.collect(context, mapOf("tmdb.key" to "k", "tmdb.region" to "PK", "account.auth" to "x", "playback.last_links" to "[]"))
        assertEquals("k", layers.secrets.getJSONObject("store").getString("tmdb.key"))
        assertEquals("PK", layers.settings.getJSONObject("store").getString("tmdb.region"))
        assertFalse(layers.secrets.toString().contains("account.auth")); assertFalse(layers.settings.toString().contains("account.auth"))
        assertFalse(layers.secrets.toString().contains("last_links"))
    }

    @Test fun `preference files and local progress survive a round trip, tracker outboxes do not travel`() {
        val playback = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        playback.edit().putString("recent", "[{\"key\":\"media:tt1:0:0\"}]").putStringSet("hiddenContinue", setOf("key:a"))
            .putString("pending.wetrakr", "[1]").commit()
        context.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE).edit().putBoolean("on", true).putInt("size", 7)
            .putLong("long", 9L).putFloat("scale", 1.5f).commit()
        val layers = BackupSnapshot.collect(context, emptyMap())
        val prefs = layers.settings.getJSONObject("prefs")
        assertFalse(prefs.getJSONObject("playback").has("pending.wetrakr"))
        // Wipe, restore, compare.
        playback.edit().clear().commit(); context.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE).edit().clear().commit()
        BackupSnapshot.apply(context, MapStore(), JSONObject(layers.settings.toString()))
        assertEquals("[{\"key\":\"media:tt1:0:0\"}]", playback.getString("recent", null))
        assertEquals(setOf("key:a"), playback.getStringSet("hiddenContinue", null))
        assertNull(playback.getString("pending.wetrakr", null))
        val subtitles = context.getSharedPreferences("pov_subtitles", Context.MODE_PRIVATE)
        assertTrue(subtitles.getBoolean("on", false)); assertEquals(7, subtitles.getInt("size", 0))
        assertEquals(9L, subtitles.getLong("long", 0)); assertEquals(1.5f, subtitles.getFloat("scale", 0f), 0.0001f)
        assertEquals(layers.fingerprint(), BackupSnapshot.collect(context, emptyMap()).fingerprint())
    }

    @Test fun `a restore never writes account keys`() {
        val store = MapStore()
        BackupSnapshot.apply(context, store, JSONObject().put("v", 1).put("store", JSONObject().put("tmdb.key", "k").put("account.auth", "evil")))
        assertEquals("k", store.get("tmdb.key")); assertEquals("", store.get("account.auth"))
    }

    @Test fun `weak passwords are refused`() {
        assertNotNull(CloudAccount.passwordProblem("short"))
        assertNotNull(CloudAccount.passwordProblem("password1"))
        assertNotNull(CloudAccount.passwordProblem("aaaaaaaaaa"))
        assertNull(CloudAccount.passwordProblem("lahore-rain-42"))
    }

    /**
     * Full round trip against a real backup server. Skipped unless POV_BACKUP_E2E_EMAIL is set; the codes
     * 123456 (signup) and 654321 (reset) must be put in the server's `codes` table for that email beforehand.
     * POV_PHONE_SIM, when set, is a node script that plays the phone page's part of phone sign-in.
     */
    @Test fun `live account round trip`() {
        val email = System.getenv("POV_BACKUP_E2E_EMAIL") ?: return
        val device = MapStore(mutableMapOf("tmdb.key" to "tmdb-secret-1", "tmdb.region" to "PK", "sources.providers.v1" to "[\"https://addon.example/abc/manifest.json\"]"))
        val tv = CloudAccount(context, device)
        val recovery = tv.signUp(email, "first-password-1", "123456")
        assertTrue(tv.signedIn); assertTrue(tv.lastBackup > 0)
        assertFalse(tv.backup())  // nothing changed

        val phone = CloudAccount(context, MapStore())
        val restored = phone.signIn(email, "first-password-1")
        assertEquals("tmdb-secret-1", restored.secrets!!.getJSONObject("store").getString("tmdb.key"))
        assertEquals("PK", restored.settings!!.getJSONObject("store").getString("tmdb.region"))
        val wrong = assertThrows(ApiException::class.java) { CloudAccount(context, MapStore()).signIn(email, "not-the-password") }
        assertEquals(401, wrong.code)

        device.put("tmdb.key", "tmdb-secret-2")
        assertTrue(tv.backup())
        assertEquals("tmdb-secret-2", CloudAccount(context, MapStore()).signIn(email, "first-password-1").secrets!!.getJSONObject("store").getString("tmdb.key"))

        tv.changePassword("first-password-1", "second-password-2")
        assertEquals(401, assertThrows(ApiException::class.java) { CloudAccount(context, MapStore()).signIn(email, "first-password-1") }.code)
        // The other device notices on its next backup and signs out.
        assertEquals(401, assertThrows(ApiException::class.java) { phone.backup(force = true) }.code)
        assertFalse(phone.signedIn)

        // Forgotten password, recovery key kept: everything comes back.
        val fresh = CloudAccount(context, MapStore())
        val session = fresh.verifyReset(email, "654321")
        assertThrows(IllegalArgumentException::class.java) { fresh.finishReset(session, "third-password-3", "AAAAA-AAAAA-AAAAA-AAAAA-AAAAA") }
        val result = fresh.finishReset(session, "third-password-3", recovery.lowercase())
        assertNull(result.newRecoveryKey)
        assertEquals("tmdb-secret-2", result.restored.secrets!!.getJSONObject("store").getString("tmdb.key"))
        assertEquals("tmdb-secret-2", CloudAccount(context, MapStore()).signIn(email, "third-password-3").secrets!!.getJSONObject("store").getString("tmdb.key"))

        System.getenv("POV_PHONE_SIM")?.let { script ->
            val link = fresh.PhoneLink()
            val address = link.start()
            assertNull(link.poll())
            val node = ProcessBuilder("node", script, address, email, "third-password-3").redirectErrorStream(true).start()
            val output = node.inputStream.bufferedReader().readText()
            assertEquals(output, 0, node.waitFor())
            assertEquals(email to "third-password-3", link.poll())
        }

        fresh.deleteAccount()
        assertFalse(fresh.signedIn)
        assertEquals(401, assertThrows(ApiException::class.java) { CloudAccount(context, MapStore()).signIn(email, "third-password-3") }.code)
    }
}
