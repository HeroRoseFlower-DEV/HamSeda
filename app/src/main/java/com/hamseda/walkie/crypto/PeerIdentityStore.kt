package com.hamseda.walkie.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Locale

/** Persistent identity contract used by the v5 trust-on-first-use handshake. */
interface PeerIdentityStore {
    fun localIdentityId(): ByteArray
    /** X.509-encoded P-256 identity-signing public key. */
    fun localPublicKey(): ByteArray
    fun signIdentity(message: ByteArray): ByteArray
    /** Defensive copy of the pinned key, or null when this identity ID is unknown. */
    fun trustedPublicKey(identityId: ByteArray): ByteArray?
    /** Pins a new ID/key binding; never replaces a key already pinned to that ID. */
    fun pinPeer(identityId: ByteArray, publicKey: ByteArray): Boolean
    fun listTrustedPeers(): List<TrustedPeerIdentity>
    fun forgetPeer(identityIdHex: String): Boolean
}

data class TrustedPeerIdentity(
    val identityIdHex: String,
    val fingerprintHex: String,
)

object PeerIdentityEncoding {
    const val ID_BYTES = 16
    private const val HEX = "0123456789ABCDEF"

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    fun fromHex(value: String): ByteArray? {
        if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) return null
        return runCatching {
            ByteArray(value.length / 2) { index ->
                ((value[index * 2].digitToInt(16) shl 4) or
                    value[index * 2 + 1].digitToInt(16)).toByte()
            }
        }.getOrNull()
    }

    fun canonicalIdentityId(value: String): String? {
        val canonical = value.uppercase(Locale.ROOT)
        return canonical.takeIf { it.matches(Regex("[0-9A-F]{32}")) }
    }

    fun fingerprintHex(publicKey: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(publicKey))
}

/** Platform-neutral store for unit tests and non-persistent callers. */
class MemoryPeerIdentityStore : PeerIdentityStore {
    private val lock = Any()
    private val random = SecureRandom()
    private val identityId = ByteArray(PeerIdentityEncoding.ID_BYTES).also(random::nextBytes)
    private val identityKey: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }
    private val pins = linkedMapOf<String, ByteArray>()

    override fun localIdentityId(): ByteArray = identityId.copyOf()
    override fun localPublicKey(): ByteArray = identityKey.public.encoded.copyOf()

    override fun signIdentity(message: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identityKey.private, random)
        signer.update(message)
        return signer.sign()
    }

    override fun trustedPublicKey(identityId: ByteArray): ByteArray? {
        if (identityId.size != PeerIdentityEncoding.ID_BYTES) return null
        return synchronized(lock) { pins[PeerIdentityEncoding.toHex(identityId)]?.copyOf() }
    }

    override fun pinPeer(identityId: ByteArray, publicKey: ByteArray): Boolean {
        require(identityId.size == PeerIdentityEncoding.ID_BYTES) { "identity ID must be 16 bytes" }
        SessionCrypto.decodePublicKey(publicKey)
        val id = PeerIdentityEncoding.toHex(identityId)
        synchronized(lock) {
            val current = pins[id]
            if (current != null) return MessageDigest.isEqual(current, publicKey)
            pins[id] = publicKey.copyOf()
            return true
        }
    }

    override fun listTrustedPeers(): List<TrustedPeerIdentity> = synchronized(lock) {
        pins.map { (id, key) ->
            TrustedPeerIdentity(id, PeerIdentityEncoding.fingerprintHex(key))
        }.sortedBy { it.identityIdHex }
    }

    override fun forgetPeer(identityIdHex: String): Boolean {
        val id = PeerIdentityEncoding.canonicalIdentityId(identityIdHex) ?: return false
        return synchronized(lock) { pins.remove(id)?.let { it.fill(0); true } ?: false }
    }
}
