package dev.pov.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All account values are encrypted with a non-exportable Android Keystore AES key. */
class SecureStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_accounts_encrypted", Context.MODE_PRIVATE)

    fun has(key: String): Boolean = get(key).isNotBlank()

    fun get(key: String, default: String = ""): String = synchronized(lock) {
        val value = preferences.getString(key, null) ?: return@synchronized default
        // A Keystore decrypt costs ~20 ms on TV hardware; a value is decrypted once and reused while its stored
        // ciphertext is unchanged, so a write from any instance (or a cleared store) is always seen.
        decrypted[key]?.takeIf { it.first == value }?.let { return@synchronized it.second }
        try {
            val parts = value.split(':')
            check(parts.size == 3 && parts[0] == "v1")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8).also { decrypted[key] = value to it }
        } catch (_: Exception) {
            // Never silently replace unreadable credentials, including after device restore.
            throw IllegalStateException("Saved account data could not be decrypted. Disconnect this account and link it again.")
        }
    }

    /** Every stored value that can be read, for the account backup. Unreadable values are left out, never replaced. */
    fun entries(): Map<String, String> = preferences.all.keys.sorted().mapNotNull { key ->
        try { key to get(key) } catch (_: Exception) { null }
    }.toMap()

    fun put(key: String, value: String) = putAll(mapOf(key to value))

    /** Persist rotating OAuth tokens in one transaction before making another request. */
    fun putAll(values: Map<String, String>) = synchronized(lock) {
        val encrypted = values.mapValues { (name, value) ->
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
                "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)}"
            } catch (_: Exception) {
                throw IllegalStateException("Account data could not be encrypted on this device.")
            }
        }
        val editor = preferences.edit()
        encrypted.forEach { (name, value) -> editor.putString(name, value) }
        check(editor.commit()) { "Account data could not be saved. Please check device storage." }
        encrypted.forEach { (name, value) -> decrypted[name] = value to values.getValue(name) }
    }

    /**
     * After the device's Keystore lost its key (an OTA or a restore on some boxes) the saved values can never be read
     * again, and every read throws, so the app crashed at launch with no way to reach "disconnect". Called at start:
     * values that can't be read are removed only when that is certain to be permanent (the key is gone, or the key
     * works but those values don't decrypt with it). A Keystore that is merely failing right now removes nothing.
     * Returns how many values were removed.
     */
    fun recoverUnreadable(): Int = synchronized(lock) {
        val unreadable = preferences.all.keys.filter { name -> runCatching { get(name) }.isFailure }
        if (unreadable.isEmpty()) return@synchronized 0
        val hasKey = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS) }.getOrNull()
            ?: return@synchronized 0                                          // the Keystore isn't answering: keep everything
        if (hasKey && !runCatching { keyWorks() }.getOrDefault(false)) return@synchronized 0
        val editor = preferences.edit()
        unreadable.forEach { editor.remove(it); decrypted.remove(it) }
        if (!editor.commit()) return@synchronized 0
        if (!hasKey) key = null                                              // a new key is made on the next save
        unreadable.size
    }

    /** Encrypts and decrypts a test value with the account key: whether the Keystore key itself is usable. */
    private fun keyWorks(): Boolean {
        val sample = byteArrayOf(1, 2, 3, 4)
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val sealed = encrypt.doFinal(sample)
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, encrypt.iv)) }
        return decrypt.doFinal(sealed).contentEquals(sample)
    }

    fun remove(key: String) = synchronized(lock) {
        check(preferences.edit().remove(key).commit()) { "Account data could not be removed." }
        decrypted.remove(key)
    }

    private fun encryptionKey(): SecretKey {
        key?.let { return it }
        return loadKey().also { key = it }
    }

    private fun loadKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        // A missing key with existing encrypted preferences is a restore/key-loss error.
        check(preferences.all.isEmpty()) { "Account encryption key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    companion object {
        private const val ALIAS = "dev.pov.tv.account.v1"
        private val lock = Any()
        /** Stored ciphertext and its plaintext, per key; guarded by [lock]. */
        private val decrypted = HashMap<String, Pair<String, String>>()
        private var key: SecretKey? = null
    }
}
