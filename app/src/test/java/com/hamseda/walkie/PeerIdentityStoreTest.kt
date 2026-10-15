package com.hamseda.walkie

import com.hamseda.walkie.crypto.MemoryPeerIdentityStore
import com.hamseda.walkie.crypto.PeerIdentityEncoding
import com.hamseda.walkie.crypto.SessionCrypto
import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.proto.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdentityStoreTest {
    @Test
    fun identityProofSignatureBindsTheExactEphemeralExchange() {
        val identity = MemoryPeerIdentityStore()
        val ephemeral = SessionCrypto.encodePublicKey(SessionCrypto.generateEphemeralKeyPair().public)
        val id = identity.localIdentityId()
        val identityPublicKey = identity.localPublicKey()
        val nonce = SessionCrypto.newNonce16()
        val salt = SessionCrypto.newCommitmentSalt()
        val hello = byteArrayOf(Protocol.VERSION, CodecId.OPUS)
        val proof = SessionCrypto.identityProofInput(id, identityPublicKey, ephemeral, nonce, hello, salt)
        val signature = identity.signIdentity(proof)
        try {
            assertTrue(SessionCrypto.verifyIdentitySignature(identityPublicKey, proof, signature))
            val altered = proof.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFalse(SessionCrypto.verifyIdentitySignature(identityPublicKey, altered, signature))
            altered.fill(0)
        } finally {
            proof.fill(0)
            signature.fill(0)
            nonce.fill(0)
            salt.fill(0)
            hello.fill(0)
        }
    }

    @Test
    fun commitmentBindsIdentityIdAndSigningKey() {
        val peer = MemoryPeerIdentityStore()
        val ephemeral = SessionCrypto.encodePublicKey(SessionCrypto.generateEphemeralKeyPair().public)
        val id = peer.localIdentityId()
        val identityKey = peer.localPublicKey()
        val nonce = SessionCrypto.newNonce16()
        val salt = SessionCrypto.newCommitmentSalt()
        val hello = byteArrayOf(Protocol.VERSION, CodecId.OPUS)
        val base = SessionCrypto.keyCommitment(ephemeral, nonce, hello, salt, id, identityKey)
        try {
            val changedId = id.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val changedIdCommitment = SessionCrypto.keyCommitment(ephemeral, nonce, hello, salt, changedId, identityKey)
            val otherKey = MemoryPeerIdentityStore().localPublicKey()
            val changedKeyCommitment = SessionCrypto.keyCommitment(ephemeral, nonce, hello, salt, id, otherKey)
            assertFalse(base.contentEquals(changedIdCommitment))
            assertFalse(base.contentEquals(changedKeyCommitment))
            changedId.fill(0)
            changedIdCommitment.fill(0)
            changedKeyCommitment.fill(0)
            otherKey.fill(0)
        } finally {
            base.fill(0)
            nonce.fill(0)
            salt.fill(0)
            hello.fill(0)
        }
    }

    @Test
    fun aPinnedIdentityCannotBeSilentlyReplacedAndCanBeForgotten() {
        val local = MemoryPeerIdentityStore()
        val peer = MemoryPeerIdentityStore()
        val attacker = MemoryPeerIdentityStore()
        val peerId = peer.localIdentityId()
        val peerKey = peer.localPublicKey()
        assertTrue(local.pinPeer(peerId, peerKey))
        assertTrue(local.pinPeer(peerId, peerKey))
        assertFalse(local.pinPeer(peerId, attacker.localPublicKey()))
        assertTrue(peerKey.contentEquals(local.trustedPublicKey(peerId)!!))
        assertEquals(1, local.listTrustedPeers().size)
        val idHex = PeerIdentityEncoding.toHex(peerId)
        assertTrue(local.forgetPeer(idHex.lowercase()))
        assertNull(local.trustedPublicKey(peerId))
        assertFalse(local.forgetPeer(idHex))
        assertNotNull(local.localIdentityId())
    }
}
