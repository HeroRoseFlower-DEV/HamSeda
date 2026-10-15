package com.hamseda.walkie.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The persistent identity private key is non-exportable in Android Keystore.
 * Peer public keys and random identity IDs are stored in app-private preferences;
 * backup is disabled in the application manifest.
 */
class AndroidPeerIdentityStore(context: Context) : PeerIdentityStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )

    override fun localIdentityId(): ByteArray {
        // If Keystore had to recreate the signing key, do not reuse its previous ID.
        identityKeyPair()
        synchronized(KEY_LOCK) {
            val saved = prefs.getString(LOCAL_ID_KEY, null)
            if (saved != null) {
                val decoded = PeerIdentityEncoding.fromHex(saved)
                if (decoded != null && decoded.size == PeerIdentityEncoding.ID_BYTES) return decoded
            }
            val fresh = ByteArray(PeerIdentityEncoding.ID_BYTES).also(SecureRandom()::nextBytes)
            check(prefs.edit().putString(LOCAL_ID_KEY, PeerIdentityEncoding.toHex(fresh)).commit()) {
                "could not persist local identity ID"
            }
            return fresh
        }
    }

    override fun localPublicKey(): ByteArray = identityKeyPair().public.encoded.copyOf()

    override fun signIdentity(message: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identityKeyPair().private)
        signer.update(message)
        return signer.sign()
    }

    override fun trustedPublicKey(identityId: ByteArray): ByteArray? {
        if (identityId.size != PeerIdentityEncoding.ID_BYTES) return null
        val stored = prefs.getString(PEER_KEY_PREFIX + PeerIdentityEncoding.toHex(identityId), null)
            ?: return null
        return runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
    }

    override fun pinPeer(identityId: ByteArray, publicKey: ByteArray): Boolean {
        require(identityId.size == PeerIdentityEncoding.ID_BYTES) { "identity ID must be 16 bytes" }
        SessionCrypto.decodePublicKey(publicKey)
        val key = PEER_KEY_PREFIX + PeerIdentityEncoding.toHex(identityId)
        val encoded = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        synchronized(KEY_LOCK) {
            if (prefs.contains(key)) {
                val old = prefs.getString(key, null) ?: return false
                val oldBytes = runCatching { Base64.decode(old, Base64.NO_WRAP) }.getOrNull()
                    ?: return false
                return try { MessageDigest.isEqual(oldBytes, publicKey) } finally { oldBytes.fill(0) }
            }
            if (!prefs.edit().putString(key, encoded).commit()) return false
            val verify = trustedPublicKey(identityId) ?: return false
            return try { MessageDigest.isEqual(verify, publicKey) } finally { verify.fill(0) }
        }
    }

    override fun listTrustedPeers(): List<TrustedPeerIdentity> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PEER_KEY_PREFIX) || value !is String) return@mapNotNull null
            val id = PeerIdentityEncoding.canonicalIdentityId(key.removePrefix(PEER_KEY_PREFIX))
                ?: return@mapNotNull null
            val publicKey = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()
                ?: return@mapNotNull null
            try {
                TrustedPeerIdentity(id, PeerIdentityEncoding.fingerprintHex(publicKey))
            } finally {
                publicKey.fill(0)
            }
        }.sortedBy { it.identityIdHex }

    override fun forgetPeer(identityIdHex: String): Boolean {
        val id = PeerIdentityEncoding.canonicalIdentityId(identityIdHex) ?: return false
        val key = PEER_KEY_PREFIX + id
        synchronized(KEY_LOCK) {
            if (!prefs.contains(key)) return false
            return prefs.edit().remove(key).commit()
        }
    }

    private fun identityKeyPair(): KeyPair = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!store.containsAlias(IDENTITY_KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(IDENTITY_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generator.generateKeyPair()
            check(prefs.edit().remove(LOCAL_ID_KEY).commit()) {
                "could not reset identity ID after Keystore key creation"
            }
        }
        val certificate = store.getCertificate(IDENTITY_KEY_ALIAS)
            ?: error("Android Keystore identity certificate is missing")
        val privateKey = store.getKey(IDENTITY_KEY_ALIAS, null) as? java.security.PrivateKey
            ?: error("Android Keystore identity private key is missing")
        KeyPair(certificate.publicKey, privateKey)
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val IDENTITY_KEY_ALIAS = "hamseda_tofu_identity_v1"
        private const val PREFS_NAME = "hamseda_peer_trust_v1"
        private const val LOCAL_ID_KEY = "local_identity_id"
        private const val PEER_KEY_PREFIX = "peer_"
        private val KEY_LOCK = Any()
    }
}
