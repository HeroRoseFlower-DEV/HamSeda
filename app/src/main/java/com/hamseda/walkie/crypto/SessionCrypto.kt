package com.hamseda.walkie.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated session cryptography for HamSeda.
 *
 * Construction (all standard platform primitives, no custom ciphers):
 * 1. Each side generates an **ephemeral** ECDH key pair on NIST P-256, then
 *    commits to its public key, nonce and HELLO before revealing the public
 *    key in [MessageType.KEY_EXCHANGE][com.hamseda.walkie.proto.MessageType.KEY_EXCHANGE].
 * 2. Each side validates the peer's commitment before running ECDH and HKDF-SHA-256
 *    over the shared secret to derive
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
 * Ephemeral session keys are wiped from memory when the session ends (see
 * [SessionKeys.wipe]). A separate long-term signing key is retained by Android
 * Keystore, and peer public-key pins are stored locally for TOFU. The v5 handshake commits to each share
 * before revealing it, preventing adaptive SAS grinding against the peer's share.
 */
object SessionCrypto {
    private const val EC_CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12
    private const val INFO = "HamSeda-v5/session-keys"
    private const val SAS_INFO = "HamSeda-v5/sas"
    private const val TRANSCRIPT_INFO = "HamSeda-v5/key-confirm"
    private const val COMMIT_INFO = "HamSeda-v5/key-commitment"
    private const val IDENTITY_PROOF_INFO = "HamSeda-v5/identity-proof"

    /** Maximum accepted encoded public-key size (P-256 X.509 ≈ 91 bytes). */
    const val MAX_PUBKEY_BYTES = 256
    const val COMMITMENT_BYTES = 32
    const val COMMITMENT_SALT_BYTES = 32
    const val IDENTITY_ID_BYTES = 16
    const val MAX_IDENTITY_SIGNATURE_BYTES = 256

    /**
     * Exact wire size of [seal]'s output for a given plaintext length:
     * 12-byte nonce + ciphertext + 16-byte GCM tag.
     */
    fun sealedLength(plaintextLen: Int): Int = NONCE_LEN + plaintextLen + GCM_TAG_BITS / 8

    private val random = SecureRandom()
    private val p256Params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(EC_CURVE))
        }.getParameterSpec(ECParameterSpec::class.java)
    }

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
        val decoded = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(bytes))
        val ecKey = decoded as? ECPublicKey
            ?: throw InvalidKeyException("peer key is not an EC public key")
        if (!isP256(ecKey.params)) {
            throw InvalidKeyException("peer key must use secp256r1/P-256")
        }
        return ecKey
    }

    /** Reject provider-accepted EC encodings that negotiate a different curve. */
    private fun isP256(actual: ECParameterSpec): Boolean {
        val expected = p256Params
        return actual.curve.field == expected.curve.field &&
            actual.curve.a == expected.curve.a &&
            actual.curve.b == expected.curve.b &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor
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
        var okm: ByteArray? = null
        try {
            okm = Hkdf.derive(sharedSecret, salt, INFO.toByteArray(), 32 + 32 + 8 + 32)
            return SessionKeys(
                audioKey = okm.copyOfRange(0, 32),
                controlKey = okm.copyOfRange(32, 64),
                sessionId = okm.copyOfRange(64, 72),
                sasSeed = okm.copyOfRange(72, 104),
            )
        } finally {
            salt.fill(0)
            okm?.fill(0)
        }
    }

    /**
     * Binds each peer's exact HELLO payload (protocol and codec), public key,
     * and associated nonce into KEY_CONFIRM. Canonical ordering follows public
     * key roles, detecting active codec/version downgrade and nonce reassignment.
     */
    fun transcriptHash(
        ownPub: ByteArray,
        peerPub: ByteArray,
        ownNonce: ByteArray,
        peerNonce: ByteArray,
        ownHello: ByteArray,
        peerHello: ByteArray,
    ): ByteArray {
        require(ownPub.size in 1..MAX_PUBKEY_BYTES && peerPub.size in 1..MAX_PUBKEY_BYTES) {
            "public key size out of bounds"
        }
        require(ownNonce.size == 16 && peerNonce.size == 16) {
            "nonces must be 16 bytes"
        }
        require(ownHello.size == 2 && peerHello.size == 2) {
            "HELLO payloads must be exactly 2 bytes"
        }
        val ownFirst = compareBytes(ownPub, peerPub) < 0
        val (p1, p2) = if (ownFirst) ownPub to peerPub else peerPub to ownPub
        val (n1, n2) = if (ownFirst) ownNonce to peerNonce else peerNonce to ownNonce
        val (h1, h2) = if (ownFirst) ownHello to peerHello else peerHello to ownHello
        // Length-prefix variable-size public keys so field boundaries remain
        // unambiguous even if a crypto provider accepts another valid encoding.
        val domain = TRANSCRIPT_INFO.toByteArray(Charsets.UTF_8)
        val encoded = ByteBuffer.allocate(
            domain.size + 2 + p1.size + n1.size + h1.size + 2 + p2.size + n2.size + h2.size,
        ).order(ByteOrder.BIG_ENDIAN)
            .put(domain)
            .putShort(p1.size.toShort()).put(p1).put(n1).put(h1)
            .putShort(p2.size.toShort()).put(p2).put(n2).put(h2)
            .array()
        return try {
            Hkdf.sha256(encoded)
        } finally {
            domain.fill(0)
            encoded.fill(0)
        }
    }

    /**
     * Deterministic role assignment from the two public keys: the side whose
     * encoded public key sorts first is role 0 ("A"), the other is role 1
     * ("B"). Used to separate the two directions in AEAD associated data.
     */
    fun myRole(ownPub: ByteArray, peerPub: ByteArray): Byte =
        if (compareBytes(ownPub, peerPub) < 0) 0 else 1

    /**
     * Six-digit short authentication string. Commitment/reveal prevents an active
     * peer from adaptively choosing its key share after learning the other share;
     * users must still compare the displayed code on every untrusted session.
     */
    fun shortAuthString(sasSeed: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sasSeed, "HmacSHA256"))
        val digest = mac.doFinal(SAS_INFO.toByteArray())
        return try {
            var acc = 0L
            for (i in 0 until 6) acc = (acc shl 8) or (digest[i].toLong() and 0xFF)
            String.format(java.util.Locale.ROOT, "%06d", acc % 1_000_000)
        } finally {
            digest.fill(0)
        }
    }

    /**
     * Legacy helper retained for focused crypto tests. Wire protocol v5 uses
     * the identity-bound overload below.
     */
    fun keyCommitment(
        publicKey: ByteArray,
        nonce: ByteArray,
        hello: ByteArray,
        salt: ByteArray,
    ): ByteArray = keyCommitment(publicKey, nonce, hello, salt, ByteArray(0), ByteArray(0))

    /**
     * Hiding, binding commitment to the ephemeral share and the stable identity
     * presented with it. The random salt hides all committed fields until reveal.
     */
    fun keyCommitment(
        publicKey: ByteArray,
        nonce: ByteArray,
        hello: ByteArray,
        salt: ByteArray,
        identityId: ByteArray,
        identityPublicKey: ByteArray,
    ): ByteArray {
        require(publicKey.size in 1..MAX_PUBKEY_BYTES) { "public key size out of bounds" }
        require(nonce.size == 16) { "nonce must be 16 bytes" }
        require(hello.size == 2) { "HELLO payload must be exactly 2 bytes" }
        require(salt.size == COMMITMENT_SALT_BYTES) { "commitment salt must be 32 bytes" }
        require(
            (identityId.isEmpty() && identityPublicKey.isEmpty()) ||
                (identityId.size == IDENTITY_ID_BYTES && identityPublicKey.size in 1..MAX_PUBKEY_BYTES),
        ) { "identity ID/public key size mismatch" }

        val domain = COMMIT_INFO.toByteArray(Charsets.UTF_8)
        val encodedIdentityKey = if (identityPublicKey.isEmpty()) ByteArray(0) else {
            ByteBuffer.allocate(2 + identityPublicKey.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(identityPublicKey.size.toShort()).put(identityPublicKey).array()
        }
        val encoded = ByteBuffer.allocate(
            domain.size + 2 + publicKey.size + nonce.size + hello.size +
                identityId.size + encodedIdentityKey.size + salt.size,
        ).order(ByteOrder.BIG_ENDIAN)
            .put(domain)
            .putShort(publicKey.size.toShort()).put(publicKey)
            .put(nonce).put(hello)
            .put(identityId).put(encodedIdentityKey).put(salt)
            .array()
        return try {
            Hkdf.sha256(encoded)
        } finally {
            domain.fill(0)
            encodedIdentityKey.fill(0)
            encoded.fill(0)
        }
    }

    /** Canonical domain-separated input signed by the persistent identity key. */
    fun identityProofInput(
        identityId: ByteArray,
        identityPublicKey: ByteArray,
        ephemeralPublicKey: ByteArray,
        nonce: ByteArray,
        hello: ByteArray,
        salt: ByteArray,
    ): ByteArray {
        require(identityId.size == IDENTITY_ID_BYTES) { "identity ID must be 16 bytes" }
        require(identityPublicKey.size in 1..MAX_PUBKEY_BYTES) { "identity public key size out of bounds" }
        require(ephemeralPublicKey.size in 1..MAX_PUBKEY_BYTES) { "ephemeral public key size out of bounds" }
        require(nonce.size == 16) { "nonce must be 16 bytes" }
        require(hello.size == 2) { "HELLO payload must be exactly 2 bytes" }
        require(salt.size == COMMITMENT_SALT_BYTES) { "commitment salt must be 32 bytes" }

        val domain = IDENTITY_PROOF_INFO.toByteArray(Charsets.UTF_8)
        val encoded = ByteBuffer.allocate(
            domain.size + identityId.size + 2 + identityPublicKey.size +
                2 + ephemeralPublicKey.size + nonce.size + hello.size + salt.size,
        ).order(ByteOrder.BIG_ENDIAN)
            .put(domain)
            .put(identityId)
            .putShort(identityPublicKey.size.toShort()).put(identityPublicKey)
            .putShort(ephemeralPublicKey.size.toShort()).put(ephemeralPublicKey)
            .put(nonce).put(hello).put(salt)
            .array()
        domain.fill(0)
        return encoded
    }

    /** Verifies a proof and rejects EC public keys on any curve except P-256. */
    fun verifyIdentitySignature(
        identityPublicKey: ByteArray,
        message: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean = try {
        if (signatureBytes.isEmpty() || signatureBytes.size > MAX_IDENTITY_SIGNATURE_BYTES) {
            false
        } else {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(decodePublicKey(identityPublicKey))
            verifier.update(message)
            verifier.verify(signatureBytes)
        }
    } catch (_: Exception) {
        false
    }

    fun newCommitmentSalt(): ByteArray = ByteArray(COMMITMENT_SALT_BYTES).also(random::nextBytes)

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
        return try {
            nonce + ct
        } finally {
            nonce.fill(0)
            ct.fill(0)
        }
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
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ct)
        } finally {
            nonce.fill(0)
            ct.fill(0)
        }
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
class SessionKeys(
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

    override fun toString(): String = "SessionKeys(<redacted>)"
}
