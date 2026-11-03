package dev.pov.tv.data

import android.content.Context
import dev.pov.tv.BuildConfig
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * The POV account: sign-up with email and password, encrypted backup and restore, password change and reset.
 * Every network call blocks: run them on a worker thread. See [BackupCrypto] for what the server can read.
 */
class CloudAccount(private val context: Context, private val store: Store) {
    constructor(context: Context, secure: SecureStore) : this(context, object : Store {
        override fun get(key: String) = secure.get(key)
        override fun putAll(values: Map<String, String>) { secure.putAll(values) }
        override fun remove(key: String) { secure.remove(key) }
        override fun entries() = secure.entries()
    })

    /** The encrypted account store on a device; plain maps in tests. */
    interface Store {
        fun get(key: String): String
        fun put(key: String, value: String) = putAll(mapOf(key to value))
        fun putAll(values: Map<String, String>)
        fun remove(key: String)
        fun entries(): Map<String, String>
    }

    class Restored(val settings: JSONObject?, val secrets: JSONObject?) {
        val hasData get() = settings != null || secrets != null
    }
    /** After a verified reset code. */
    class ResetSession(val email: String, val token: String, val wrappedRecovery: String, val settingsKey: ByteArray,
                       val settings: String, val secrets: String)
    class ResetResult(val newRecoveryKey: String?, val restored: Restored)

    val signedIn get() = email.isNotBlank() && store.get(AUTH).isNotBlank()
    val email: String get() = store.get(EMAIL)
    var autoBackup: Boolean
        get() = store.get(AUTO) != "false"
        set(value) = store.put(AUTO, value.toString())
    var welcomeDone: Boolean
        get() = store.get(WELCOME) == "done"
        set(value) { if (value) store.put(WELCOME, "done") else store.remove(WELCOME) }
    val lastBackup: Long get() = store.get(LAST_AT).toLongOrNull() ?: 0L
    var server: String
        get() = store.get(SERVER).ifBlank { BuildConfig.BACKUP_SERVER }.trimEnd('/')
        set(value) { val clean = value.trim().trimEnd('/'); if (clean.isBlank() || clean == BuildConfig.BACKUP_SERVER) store.remove(SERVER) else store.put(SERVER, clean) }

    fun collect() = BackupSnapshot.collect(context, store.entries())

    // ---------- sign up ----------

    fun sendSignupCode(email: String) { post("/v1/signup/code", JSONObject().put("email", BackupCrypto.normalEmail(email))) }

    /** Creates the account with this device's setup and returns the recovery key to show once. */
    fun signUp(email: String, password: String, code: String): String {
        val normal = BackupCrypto.normalEmail(email)
        val login = BackupCrypto.login(normal, password)
        val dataKey = BackupCrypto.newKey(); val settingsKey = BackupCrypto.newKey()
        val recovery = BackupCrypto.newRecoveryKey()
        val layers = collect()
        val result = post("/v1/signup", JSONObject().put("email", normal).put("code", code.trim()).put("auth", login.auth)
            .put("wrapped_pw", BackupCrypto.seal(login.passwordKey, dataKey, BackupCrypto.DK_PASSWORD))
            .put("wrapped_rk", BackupCrypto.seal(BackupCrypto.recoveryKeyBytes(recovery), dataKey, BackupCrypto.DK_RECOVERY))
            .put("settings_key", BackupCrypto.encode(settingsKey))
            .put("settings", sealLayer(settingsKey, layers.settings, BackupCrypto.SETTINGS))
            .put("secrets", sealLayer(dataKey, layers.secrets, BackupCrypto.SECRETS)))
        remember(normal, login.auth, dataKey, settingsKey)
        backedUp(layers.fingerprint(), result.optLong("updated"))
        return recovery
    }

    // ---------- sign in ----------

    /** Signs this device in and returns the backup to restore. */
    fun signIn(email: String, password: String): Restored {
        val normal = BackupCrypto.normalEmail(email)
        val login = BackupCrypto.login(normal, password)
        val result = post("/v1/login", JSONObject().put("email", normal).put("auth", login.auth))
        val dataKey = try { BackupCrypto.open(login.passwordKey, result.getString("wrapped_pw"), BackupCrypto.DK_PASSWORD) }
            catch (_: Exception) { throw IllegalStateException("Your backup could not be unlocked with this password.") }
        val settingsKey = BackupCrypto.decode(result.getString("settings_key"))
        val restored = Restored(openLayer(settingsKey, result.optString("settings"), BackupCrypto.SETTINGS),
            openLayer(dataKey, result.optString("secrets"), BackupCrypto.SECRETS))
        remember(normal, login.auth, dataKey, settingsKey)
        // The device's next backup should upload whatever it has after the restore; until then the account's
        // backup is the one just restored.
        store.remove(LAST_HASH)
        result.optLong("updated").takeIf { it > 0 }?.let { store.put(LAST_AT, it.toString()) }
        return restored
    }

    fun apply(restored: Restored) {
        BackupSnapshot.apply(context, store, restored.settings)
        BackupSnapshot.apply(context, store, restored.secrets)
    }

    // ---------- backup ----------

    /** Uploads this device's setup when it changed since the last backup (or always, with [force]). */
    fun backup(force: Boolean = false): Boolean {
        check(signedIn) { "Sign in to your POV account first." }
        val layers = collect()
        val fingerprint = layers.fingerprint()
        if (!force && fingerprint == store.get(LAST_HASH)) return false
        val dataKey = BackupCrypto.decode(store.get(DATA_KEY)); val settingsKey = BackupCrypto.decode(store.get(SETTINGS_KEY))
        val result = try {
            post("/v1/backup", JSONObject().put("email", email).put("auth", store.get(AUTH))
                .put("settings", sealLayer(settingsKey, layers.settings, BackupCrypto.SETTINGS))
                .put("secrets", sealLayer(dataKey, layers.secrets, BackupCrypto.SECRETS)))
        } catch (error: ApiException) {
            // The password was changed or reset on another device: this one has to sign in again.
            if (error.code == 401) { forget(); throw ApiException(401, "Your POV account password was changed. Sign in again to keep backing up.") }
            throw error
        }
        backedUp(fingerprint, result.optLong("updated"))
        return true
    }

    // ---------- password ----------

    fun changePassword(current: String, next: String) {
        val old = BackupCrypto.login(email, current)
        if (old.auth != store.get(AUTH)) throw IllegalArgumentException("Your current password is not right.")
        val fresh = BackupCrypto.login(email, next)
        val dataKey = BackupCrypto.decode(store.get(DATA_KEY))
        post("/v1/password", JSONObject().put("email", email).put("auth", old.auth).put("new_auth", fresh.auth)
            .put("wrapped_pw", BackupCrypto.seal(fresh.passwordKey, dataKey, BackupCrypto.DK_PASSWORD)))
        store.put(AUTH, fresh.auth)
    }

    fun sendResetCode(email: String) { post("/v1/reset/code", JSONObject().put("email", BackupCrypto.normalEmail(email))) }

    fun verifyReset(email: String, code: String): ResetSession {
        val normal = BackupCrypto.normalEmail(email)
        val result = post("/v1/reset/verify", JSONObject().put("email", normal).put("code", code.trim()))
        return ResetSession(normal, result.getString("reset_token"), result.getString("wrapped_rk"),
            BackupCrypto.decode(result.getString("settings_key")), result.optString("settings"), result.optString("secrets"))
    }

    /**
     * Sets the new password. With the recovery key the same data key is kept, so everything comes back; without it
     * a new data key and recovery key are made and the old secrets are dropped (no one can open them any more).
     */
    fun finishReset(session: ResetSession, password: String, recoveryKey: String?): ResetResult {
        val oldDataKey = recoveryKey?.takeIf { it.isNotBlank() }?.let {
            try { BackupCrypto.open(BackupCrypto.recoveryKeyBytes(it), session.wrappedRecovery, BackupCrypto.DK_RECOVERY) }
            catch (_: Exception) { throw IllegalArgumentException("That recovery key is not right. Check it, or leave it empty.") }
        }
        val dataKey = oldDataKey ?: BackupCrypto.newKey()
        val newRecovery = if (oldDataKey == null) BackupCrypto.newRecoveryKey() else null
        val wrappedRecovery = newRecovery?.let { BackupCrypto.seal(BackupCrypto.recoveryKeyBytes(it), dataKey, BackupCrypto.DK_RECOVERY) } ?: session.wrappedRecovery
        val login = BackupCrypto.login(session.email, password)
        post("/v1/reset", JSONObject().put("email", session.email).put("reset_token", session.token).put("auth", login.auth)
            .put("wrapped_pw", BackupCrypto.seal(login.passwordKey, dataKey, BackupCrypto.DK_PASSWORD))
            .put("wrapped_rk", wrappedRecovery).put("secrets", if (oldDataKey != null) session.secrets else ""))
        val restored = Restored(openLayer(session.settingsKey, session.settings, BackupCrypto.SETTINGS),
            oldDataKey?.let { openLayer(it, session.secrets, BackupCrypto.SECRETS) })
        remember(session.email, login.auth, dataKey, session.settingsKey)
        store.remove(LAST_HASH); store.put(LAST_AT, System.currentTimeMillis().toString())
        return ResetResult(newRecovery, restored)
    }

    fun deleteAccount() {
        post("/v1/delete", JSONObject().put("email", email).put("auth", store.get(AUTH)))
        forget()
    }

    /** Signs this device out. Its settings stay; nothing is removed from the account. */
    fun forget() = listOf(EMAIL, AUTH, DATA_KEY, SETTINGS_KEY, LAST_HASH, LAST_AT).forEach(store::remove)

    // ---------- phone sign-in ----------

    /** The TV's side of typing the email and password on a phone. */
    inner class PhoneLink {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        private var id = ""

        /** Returns the address for the QR code. Its #fragment holds the TV's public key and never reaches the server. */
        fun start(): String {
            id = post("/v1/link", JSONObject()).getString("id")
            val pub = pair.public as ECPublicKey
            val raw = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)
            return "$server/l/$id#${BackupCrypto.encode(raw)}"
        }

        /** Null while the phone has not sent anything yet. */
        fun poll(): Pair<String, String>? {
            val result = request("GET", "/v1/link/$id", null)
            val payload = result.optString("payload").ifBlank { return null }
            val parts = payload.split('.')
            require(parts.size == 3) { "The phone sent something unreadable. Try again." }
            val point = BackupCrypto.decode(parts[0])
            require(point.size == 65 && point[0] == 4.toByte()) { "The phone sent something unreadable. Try again." }
            val params = (pair.public as ECPublicKey).params
            val phoneKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
                ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), params))
            val agreement = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(phoneKey, true) }
            val key = BackupCrypto.hkdf(agreement.generateSecret(), "pov-link:v1")
            val plain = try {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, BackupCrypto.decode(parts[1])))
                cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                cipher.doFinal(BackupCrypto.decode(parts[2]))
            } catch (_: Exception) { throw IllegalStateException("The phone sent something unreadable. Try again.") }
            val json = JSONObject(String(plain, Charsets.UTF_8))
            return json.optString("email") to json.optString("password")
        }

        private fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                else -> ByteArray(32 - bytes.size) + bytes
            }
        }
    }

    // ---------- internals ----------

    private fun remember(email: String, auth: String, dataKey: ByteArray, settingsKey: ByteArray) = store.putAll(mapOf(
        EMAIL to email, AUTH to auth, DATA_KEY to BackupCrypto.encode(dataKey), SETTINGS_KEY to BackupCrypto.encode(settingsKey)))

    private fun backedUp(fingerprint: String, updated: Long) = store.putAll(mapOf(LAST_HASH to fingerprint,
        LAST_AT to (if (updated > 0) updated else System.currentTimeMillis()).toString()))

    private fun sealLayer(key: ByteArray, layer: JSONObject, label: String) =
        BackupCrypto.seal(key, deflate(layer.toString().toByteArray(Charsets.UTF_8)), label)

    private fun openLayer(key: ByteArray, sealed: String, label: String): JSONObject? {
        if (sealed.isBlank()) return null
        val bytes = try { BackupCrypto.open(key, sealed, label) } catch (_: Exception) { throw IllegalStateException("Your backup could not be opened.") }
        return JSONObject(String(inflate(bytes), Charsets.UTF_8))
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(9)
        try {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(out, deflater).use { it.write(bytes) }
            return out.toByteArray()
        } finally { deflater.end() }
    }

    private fun inflate(bytes: ByteArray): ByteArray = java.util.zip.InflaterInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun post(path: String, body: JSONObject) = request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val target = URL(server + path)
        if (target.protocol != "https") throw ApiException(0, "The backup server must use HTTPS.")
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "POV-Rajput/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) throw ApiException(code, json.optString("error").ifBlank { "The backup server could not be reached (HTTP $code)." })
            return json
        } catch (error: ApiException) { throw error }
        catch (error: java.io.IOException) { throw ApiException(0, "The backup server could not be reached. Check your connection and try again.") }
        finally { connection.disconnect() }
    }

    companion object {
        private const val EMAIL = "account.email"
        private const val AUTH = "account.auth"
        private const val DATA_KEY = "account.data_key"
        private const val SETTINGS_KEY = "account.settings_key"
        private const val LAST_HASH = "account.last_hash"
        private const val LAST_AT = "account.last_at"
        private const val AUTO = "account.auto"
        private const val WELCOME = "account.welcome"
        private const val SERVER = "account.server"

        private val COMMON = setOf("password", "password1", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123",
            "11111111", "00000000", "iloveyou", "abcd1234", "admin123", "pakistan", "pakistan123", "letmein1", "welcome1")

        /** Null when the password is acceptable, otherwise why not. */
        fun passwordProblem(password: String): String? = when {
            password.length < 8 -> "Use at least 8 characters."
            password.lowercase() in COMMON || password.toSet().size < 3 -> "That password is too easy to guess. Pick another."
            else -> null
        }

        fun validEmail(email: String) = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())
    }
}
