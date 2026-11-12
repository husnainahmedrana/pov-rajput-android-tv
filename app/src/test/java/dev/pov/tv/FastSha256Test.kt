package dev.pov.tv

import dev.pov.tv.usenet.FastSha256
import java.security.MessageDigest
import java.security.Security
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastSha256Test {
    @Test fun `the buffered digest gives the same hash and is removed afterwards`() {
        val pieces = (0 until 5000).map { Random(it).nextBytes(Random(it).nextInt(0, 200)) } + listOf(Random(9).nextBytes(200_000))
        val plain = MessageDigest.getInstance("SHA-256").apply { pieces.forEach { update(it) } }.digest()
        val buffered = FastSha256.during {
            MessageDigest.getInstance("SHA-256").apply { pieces.forEach { update(it) }; update(7.toByte()) }.digest()
        }
        val plainWithByte = MessageDigest.getInstance("SHA-256").apply { pieces.forEach { update(it) }; update(7.toByte()) }.digest()
        assertArrayEquals(plainWithByte, buffered)
        assertNull(Security.getProvider("PovBufferedSha256"))
        assertArrayEquals(plain, MessageDigest.getInstance("SHA-256").apply { pieces.forEach { update(it) } }.digest())
    }
}
