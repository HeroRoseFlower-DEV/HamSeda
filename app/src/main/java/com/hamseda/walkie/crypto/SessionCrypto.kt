package com.hamseda.walkie.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated session cryptography for HamSeda.
 *
 * Construction (all standard platform primitives, no custom ciphers):
 * 1. Each side generates an **ephemeral** ECDH key pair on NIST P-256 and
 *    exchanges the public key in [MessageType.KEY_EXCHANGE][com.hamseda.walkie.proto.MessageType.KEY_EXCHANGE].
 * 2. Both sides run ECDH, then HKDF-SHA-256 over the shared secret to derive
 *    independent keys (key separation):
 *    - 32-byte audio key   (AES-256-GCM for AUDIO frames)
 *    - 32-byte control key (AES-256-GCM for floor-control / liveness frames)
 *    - 8-byte session id   (carried in every frame header)
 *    - 32-byte SAS seed    (short authentication string)
 * 3. Each side authenticates the exchange by sealing a transcript hash with
 *    the control key ([MessageType.KEY_CONFIRM][com.hamseda.walkie.proto.MessageType.KEY_CONFIRM]).
 * 4. Both users compare the 6-digit SAS out-of-band (read aloud / shown on
 *    screen) and confirm the match before any audio flows. The SAS is
 *    *detection*, not key material: a mismatch aborts the session.
 *
 * Frame encryption: AES-256-GCM with a fresh 96-bit random nonce per frame
 * (never reused under one key — new random nonce every seal). The 29-byte
 * frame header is passed as associated data, binding ciphertext to its
 * session/sequence/type.
 *
 * Ephemeral keys are wiped from memory when the session ends; nothing is
 * persisted (see [SessionKeys.wipe]).
 */
object SessionCrypto {
    private const val EC_CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12
    private const val INFO = "HamSeda-v1/session-keys"
    private const val SAS_INFO = "HamSeda-v1/sas"

    /** Maximum accepted encoded public-key size (P-256 X.509 ≈ 91 bytes). */
    const val MAX_PUBKEY_BYTES = 256

    /**
     * Exact wire size of [seal]'s output for a given plaintext length:
     * 12-byte nonce + ciphertext + 16-byte GCM tag.
     */
    fun sealedLength(plaintextLen: Int): Int = NONCE_LEN + plaintextLen + GCM_TAG_BITS / 8

    private val random = SecureRandom()

    // ------------------------------------------------------------------ keys

    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), random)
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun decodePublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size in 1..MAX_PUBKEY_BYTES) {
            "public key size out of bounds: ${bytes.size}"
        }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun agreeSharedSecret(own: KeyPair, peerPublic: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(own.private)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }

    // ------------------------------------------------------------- derivation

    /**
     * Derives session keys. Nonces are ordered deterministically so both
     * sides compute identical output regardless of who sent first.
     */
    fun deriveKeys(sharedSecret: ByteArray, nonceA: ByteArray, nonceB: ByteArray): SessionKeys {
        require(nonceA.size == 16 && nonceB.size == 16) { "nonces must be 16 bytes" }
        val (first, second) = ordered(nonceA, nonceB)
        val salt = Hkdf.sha256(first, second)
        val okm = Hkdf.derive(sharedSecret, salt, INFO.toByteArray(), 32 + 32 + 8 + 32)
        return SessionKeys(
            audioKey = okm.copyOfRange(0, 32),
            controlKey = okm.copyOfRange(32, 64),
            sessionId = okm.copyOfRange(64, 72),
            sasSeed = okm.copyOfRange(72, 104),
        )
    }

    /**
     * Binds the key exchange to the exact transcript both sides saw.
     * Any MITM that substituted a public key or nonce changes this hash,
     * which is verified inside the sealed KEY_CONFIRM exchange.
     */
    fun transcriptHash(
        ownPub: ByteArray,
        peerPub: ByteArray,
        ownNonce: ByteArray,
        peerNonce: ByteArray,
    ): ByteArray {
        val (p1, p2) = ordered(ownPub, peerPub)
        val (n1, n2) = ordered(ownNonce, peerNonce)
        return Hkdf.sha256(p1, p2, n1, n2)
    }

    /**
     * Deterministic role assignment from the two public keys: the side whose
     * encoded public key sorts first is role 0 ("A"), the other is role 1
     * ("B"). Used to separate the two directions in AEAD associated data.
     */
    fun myRole(ownPub: ByteArray, peerPub: ByteArray): Byte =
        if (compareBytes(ownPub, peerPub) < 0) 0 else 1

    /**
     * Six-digit short authentication string. Derived from the session's SAS
     * seed — both honest sides compute the same value; an active MITM
     * cannot make both sides display the same code without also breaking
     * the ECDH exchange it is attacking.
     */
    fun shortAuthString(sasSeed: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sasSeed, "HmacSHA256"))
        val digest = mac.doFinal(SAS_INFO.toByteArray())
        var acc = 0L
        for (i in 0 until 6) acc = (acc shl 8) or (digest[i].toLong() and 0xFF)
        return "%06d".format(acc % 1_000_000)
    }

    fun newNonce16(): ByteArray = ByteArray(16).also(random::nextBytes)

    // ------------------------------------------------------------- AEAD seal

    /**
     * Seals [plaintext]: returns `nonce(12) || ciphertext || tag(16)`.
     * [associatedData] is typically the frame header (binds the ciphertext
     * to its session id, sequence number, and type).
     */
    fun seal(key: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "audio/control keys must be 32 bytes" }
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
            random,
        )
        cipher.updateAAD(associatedData)
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    /**
     * Opens a sealed payload. Throws [javax.crypto.AEADBadTagException] (or
     * [IllegalArgumentException] on malformed input) when authentication
     * fails — callers must drop the frame and count it as an auth failure.
     */
    fun open(key: ByteArray, associatedData: ByteArray, sealed: ByteArray): ByteArray {
        require(key.size == 32) { "audio/control keys must be 32 bytes" }
        require(sealed.size >= NONCE_LEN + 16) {
            "sealed payload too short: ${sealed.size}"
        }
        val nonce = sealed.copyOfRange(0, NONCE_LEN)
        val ct = sealed.copyOfRange(NONCE_LEN, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ct)
    }

    // ------------------------------------------------------------------ util

    private fun ordered(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> =
        if (compareBytes(a, b) <= 0) a to b else b to a

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

/** Ephemeral session key material. Call [wipe] when the session ends. */
data class SessionKeys(
    val audioKey: ByteArray,
    val controlKey: ByteArray,
    val sessionId: ByteArray,
    val sasSeed: ByteArray,
) {
    /** Zeroes all key material. Idempotent. */
    fun wipe() {
        audioKey.fill(0)
        controlKey.fill(0)
        sessionId.fill(0)
        sasSeed.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionKeys) return false
        return audioKey.contentEquals(other.audioKey) &&
            controlKey.contentEquals(other.controlKey) &&
            sessionId.contentEquals(other.sessionId) &&
            sasSeed.contentEquals(other.sasSeed)
    }

    override fun hashCode(): Int {
        var r = audioKey.contentHashCode()
        r = 31 * r + controlKey.contentHashCode()
        r = 31 * r + sessionId.contentHashCode()
        r = 31 * r + sasSeed.contentHashCode()
        return r
    }
}
