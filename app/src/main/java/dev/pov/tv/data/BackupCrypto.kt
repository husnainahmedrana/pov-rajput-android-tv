package dev.pov.tv.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Account backup encryption, done entirely on the device.
 *
 * The password gives two unrelated values (PBKDF2-HMAC-SHA256, salted with the email): a key that unlocks the data
 * key, and a login token. Only the login token is sent, and the server keeps only a hash of it. The random data key
 * encrypts the secrets layer; it is stored on the server only locked with the password and with the recovery key.
 */
object BackupCrypto {
    const val ITERATIONS = 210_000
    private val random = SecureRandom()
    private val url = Base64.getUrlEncoder().withoutPadding()
    private val unurl = Base64.getUrlDecoder()
    private const val RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ0123456789"  // Crockford-style: no I, L, O, U

    class Login(val passwordKey: ByteArray, val auth: String)

    fun normalEmail(email: String): String = email.trim().lowercase()

    fun login(email: String, password: String, iterations: Int = ITERATIONS): Login {
        val salt = sha256("pov-backup-salt:v1:${normalEmail(email)}".toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 512)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return Login(bytes.copyOfRange(0, 32), url.encodeToString(bytes.copyOfRange(32, 64)))
    }

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** 25 characters (125 bits) in five groups of five, easy to read off a TV and type back. */
    fun newRecoveryKey(): String {
        val chars = CharArray(25) { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }
        return String(chars).chunked(5).joinToString("-")
    }

    fun normalRecoveryKey(text: String): String = text.uppercase().filter { it in RECOVERY_ALPHABET || it in "ILOU" }
        .map { when (it) { 'I', 'L' -> '1'; 'O' -> '0'; 'U' -> 'V'; else -> it } }.joinToString("")

    fun validRecoveryKey(text: String) = normalRecoveryKey(text).length == 25

    fun recoveryKeyBytes(text: String): ByteArray = sha256("pov-recovery:v1:${normalRecoveryKey(text)}".toByteArray(Charsets.UTF_8))

    fun seal(key: ByteArray, plain: ByteArray, label: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        return "v1:${url.encodeToString(iv)}:${url.encodeToString(cipher.doFinal(plain))}"
    }

    /** Throws when the key is wrong or the text was changed. */
    fun open(key: ByteArray, sealed: String, label: String): ByteArray {
        val parts = sealed.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Unreadable backup data." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unurl.decode(parts[1])))
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(unurl.decode(parts[2]))
    }

    fun encode(bytes: ByteArray): String = url.encodeToString(bytes)
    fun decode(text: String): ByteArray = unurl.decode(text)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hkdf(secret: ByteArray, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = java.io.ByteArrayOutputStream(); var block = ByteArray(0); var counter = 1
        while (out.size() < length) {
            mac.update(block); mac.update(info.toByteArray(Charsets.UTF_8)); mac.update(counter.toByte())
            block = mac.doFinal(); out.write(block); counter++
        }
        return out.toByteArray().copyOf(length)
    }

    const val DK_PASSWORD = "pov-dk-password:v1"
    const val DK_RECOVERY = "pov-dk-recovery:v1"
    const val SECRETS = "pov-secrets:v1"
    const val SETTINGS = "pov-settings:v1"
}
