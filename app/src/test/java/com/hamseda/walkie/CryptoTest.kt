package com.hamseda.walkie

import com.hamseda.walkie.crypto.Hkdf
import com.hamseda.walkie.crypto.ReplayProtection
import com.hamseda.walkie.crypto.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoTest {

    private fun ecdhPair(): Triple<SessionCryptoKeys, SessionCryptoKeys, ByteArray> {
        val a = SessionCrypto.generateEphemeralKeyPair()
        val b = SessionCrypto.generateEphemeralKeyPair()
        val pubA = SessionCrypto.encodePublicKey(a.public)
        val pubB = SessionCrypto.encodePublicKey(b.public)
        val secretA = SessionCrypto.agreeSharedSecret(a, SessionCrypto.decodePublicKey(pubB))
        val secretB = SessionCrypto.agreeSharedSecret(b, SessionCrypto.decodePublicKey(pubA))
        assertArrayEquals(secretA, secretB)
        val nonceA = SessionCrypto.newNonce16()
        val nonceB = SessionCrypto.newNonce16()
        val keysA = SessionCrypto.deriveKeys(secretA, nonceA, nonceB)
        val keysB = SessionCrypto.deriveKeys(secretB, nonceB, nonceA)
        return Triple(
            SessionCryptoKeys(pubA, nonceA, keysA),
            SessionCryptoKeys(pubB, nonceB, keysB),
            secretA,
        )
    }

    private data class SessionCryptoKeys(
        val pub: ByteArray,
        val nonce: ByteArray,
        val keys: com.hamseda.walkie.crypto.SessionKeys,
    )

    @Test
    fun `both sides derive identical session keys`() {
        val (a, b, _) = ecdhPair()
        assertEquals(a.keys, b.keys)
    }

    @Test
    fun `key separation - audio and control keys differ`() {
        val (a, _, _) = ecdhPair()
        assertFalse(a.keys.audioKey.contentEquals(a.keys.controlKey))
        assertEquals(8, a.keys.sessionId.size)
        assertEquals(32, a.keys.sasSeed.size)
    }

    @Test
    fun `hkdf is deterministic`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { 7 }
        val info = "test".toByteArray()
        assertArrayEquals(
            Hkdf.derive(ikm, salt, info, 64),
            Hkdf.derive(ikm, salt, info, 64),
        )
    }

    @Test
    fun `aes-gcm seal open roundtrip`() {
        val (a, _, _) = ecdhPair()
        val aad = "header".toByteArray()
        val plaintext = "hello hamseda".toByteArray()
        val sealed = SessionCrypto.seal(a.keys.audioKey, aad, plaintext)
        assertArrayEquals(plaintext, SessionCrypto.open(a.keys.audioKey, aad, sealed))
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val (a, _, _) = ecdhPair()
        val aad = "header".toByteArray()
        val sealed = SessionCrypto.seal(a.keys.audioKey, aad, "data".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            SessionCrypto.open(a.keys.audioKey, aad, sealed)
        }
    }

    @Test
    fun `wrong key fails authentication`() {
        val (a, b, _) = ecdhPair()
        // Derive a *different* session to get an unrelated key.
        val (_, b2, _) = ecdhPair()
        val aad = "header".toByteArray()
        val sealed = SessionCrypto.seal(a.keys.audioKey, aad, "data".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            SessionCrypto.open(b2.keys.audioKey, aad, sealed)
        }
        assertNotEquals(
            a.keys.audioKey.contentHashCode(),
            b2.keys.audioKey.contentHashCode(),
        )
    }

    @Test
    fun `wrong associated data fails authentication`() {
        val (a, _, _) = ecdhPair()
        val sealed = SessionCrypto.seal(a.keys.audioKey, "aad-1".toByteArray(), "data".toByteArray())
        assertThrows(AEADBadTagException::class.java) {
            SessionCrypto.open(a.keys.audioKey, "aad-2".toByteArray(), sealed)
        }
    }

    @Test
    fun `nonces are unique per seal`() {
        val (a, _, _) = ecdhPair()
        val aad = "h".toByteArray()
        val s1 = SessionCrypto.seal(a.keys.audioKey, aad, "same".toByteArray())
        val s2 = SessionCrypto.seal(a.keys.audioKey, aad, "same".toByteArray())
        assertFalse(s1.contentEquals(s2))
    }

    @Test
    fun `both sides compute the same SAS`() {
        val (a, b, _) = ecdhPair()
        val sasA = SessionCrypto.shortAuthString(a.keys.sasSeed)
        val sasB = SessionCrypto.shortAuthString(b.keys.sasSeed)
        assertEquals(sasA, sasB)
        assertEquals(6, sasA.length)
        assertTrue(sasA.all { it.isDigit() })
    }

    @Test
    fun `different transcripts give different SAS with overwhelming probability`() {
        val (a, _, _) = ecdhPair()
        val (_, b2, _) = ecdhPair()
        assertNotEquals(
            SessionCrypto.shortAuthString(a.keys.sasSeed),
            SessionCrypto.shortAuthString(b2.keys.sasSeed),
        )
    }

    @Test
    fun `transcript hash binds both public keys and nonces`() {
        val (a, b, _) = ecdhPair()
        val t1 = SessionCrypto.transcriptHash(a.pub, b.pub, a.nonce, b.nonce)
        val t2 = SessionCrypto.transcriptHash(b.pub, a.pub, b.nonce, a.nonce)
        assertArrayEquals(t1, t2) // order-independent
        val tamperedPub = b.pub.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(t1.contentEquals(SessionCrypto.transcriptHash(a.pub, tamperedPub, a.nonce, b.nonce)))
    }

    @Test
    fun `roles are complementary and deterministic`() {
        val (a, b, _) = ecdhPair()
        val roleA = SessionCrypto.myRole(a.pub, b.pub)
        val roleB = SessionCrypto.myRole(b.pub, a.pub)
        assertNotEquals(roleA, roleB)
        assertTrue(roleA in setOf<Byte>(0, 1))
    }

    @Test
    fun `malformed public key is rejected`() {
        assertThrows(Exception::class.java) {
            SessionCrypto.decodePublicKey(ByteArray(10) { 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionCrypto.decodePublicKey(ByteArray(300))
        }
    }

    @Test
    fun `wipe zeroes key material`() {
        val (a, _, _) = ecdhPair()
        a.keys.wipe()
        assertTrue(a.keys.audioKey.all { it == 0.toByte() })
        assertTrue(a.keys.controlKey.all { it == 0.toByte() })
        assertTrue(a.keys.sessionId.all { it == 0.toByte() })
        assertTrue(a.keys.sasSeed.all { it == 0.toByte() })
        a.keys.wipe() // idempotent
    }
}

class ReplayProtectionTest {

    @Test
    fun `in-order frames accepted`() {
        val r = ReplayProtection()
        assertTrue(r.accept(0))
        assertTrue(r.accept(1))
        assertTrue(r.accept(2))
    }

    @Test
    fun `duplicate is rejected`() {
        val r = ReplayProtection()
        assertTrue(r.accept(5))
        assertFalse(r.accept(5))
    }

    @Test
    fun `too-old frame is rejected`() {
        val r = ReplayProtection(windowSize = 8)
        repeat(20) { r.accept(it.toLong()) }
        assertFalse(r.accept(5)) // 5 <= 19 - 8
    }

    @Test
    fun `out-of-order within window is accepted once`() {
        val r = ReplayProtection(windowSize = 8)
        assertTrue(r.accept(10))
        assertTrue(r.accept(12)) // gap
        assertTrue(r.accept(11)) // fills the gap
        assertFalse(r.accept(11)) // now a duplicate
    }

    @Test
    fun `far jump resets the window`() {
        val r = ReplayProtection(windowSize = 8)
        assertTrue(r.accept(3))
        assertTrue(r.accept(1000))
        assertTrue(r.accept(1001))
        assertFalse(r.accept(3)) // ancient history
    }

    @Test
    fun `reset clears state`() {
        val r = ReplayProtection()
        assertTrue(r.accept(7))
        r.reset()
        assertTrue(r.accept(7))
    }
}
