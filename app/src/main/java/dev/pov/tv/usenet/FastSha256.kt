package dev.pov.tv.usenet

import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.Security

/**
 * The 7z library derives an encrypted archive's key by hashing salt + password + counter 2^19 times, three tiny digest
 * calls per round. On a TV each call crosses into native code, so that alone took ~10 s on the onn 4K. While a 7z
 * directory is read, SHA-256 is served by this wrapper, which gathers the small pieces and hands them to the real
 * SHA-256 in large blocks. The hash of the same bytes is the same, so the key and every other digest are unchanged.
 */
internal object FastSha256 {
    private const val NAME = "PovBufferedSha256"
    private val lock = Any()
    @Volatile internal var real: Provider? = null

    fun <T> during(block: () -> T): T = synchronized(lock) {
        val installed = try {
            if (real == null) real = Security.getProviders("MessageDigest.SHA-256")?.firstOrNull { it.name != NAME }
            real != null && Security.getProvider(NAME) == null && Security.insertProviderAt(Holder(), 1) > 0
        } catch (_: Exception) { false }                       // no wrapper: just slower, still correct
        try { block() } finally { if (installed) try { Security.removeProvider(NAME) } catch (_: Exception) {} }
    }

    private class Holder : Provider(NAME, 1.0, "Buffered SHA-256 for 7z key derivation") {
        init { put("MessageDigest.SHA-256", Buffered::class.java.name) }
    }

    class Buffered : MessageDigestSpi() {
        private val digest = real?.let { MessageDigest.getInstance("SHA-256", it) } ?: MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(64 * 1024)
        private var filled = 0
        private fun flush() { if (filled > 0) { digest.update(buffer, 0, filled); filled = 0 } }
        override fun engineUpdate(input: Byte) { if (filled == buffer.size) flush(); buffer[filled++] = input }
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
            if (len >= buffer.size) { flush(); digest.update(input, offset, len); return }
            if (filled + len > buffer.size) flush()
            System.arraycopy(input, offset, buffer, filled, len); filled += len
        }
        override fun engineDigest(): ByteArray { flush(); return digest.digest() }
        override fun engineReset() { filled = 0; digest.reset() }
        override fun engineGetDigestLength() = 32
    }
}
