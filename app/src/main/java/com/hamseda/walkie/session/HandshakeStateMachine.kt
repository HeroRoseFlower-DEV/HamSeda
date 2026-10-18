package com.hamseda.walkie.session

import com.hamseda.walkie.crypto.SessionCrypto
import com.hamseda.walkie.crypto.SessionKeys
import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.proto.Protocol
import java.security.KeyPair

/**
 * Encapsulates the session handshake protocol state (ephemeral keys, nonces,
 * commitments, peer identity material, and per-phase sent flags).
 *
 * Extracted from SessionManager (finding I8) to shrink the god class. This is
 * a STATE HOLDER, not a threading owner: SessionManager retains the frame
 * pump, lifecycle, and all locking. All methods here are plain synchronous
 * state transitions.
 *
 * Actual locking discipline (not "hold a SessionManager lock for everything"):
 * - [wipe] runs under teardownMutex (teardown()) or uncontended at close();
 *   teardown is the only writer at that point, so the wipe is serialized.
 * - Handshake-path mutations (beginHandshake, frame handlers) run under
 *   handshakeFrameLock.
 * - Hot-path reads (enqueueControl/enqueueAudio reading keys/myRole) are
 *   intentionally lock-free snapshots: the fields are only replaced (never
 *   mutated in place) after the handshake completes, and a torn-down
 *   session null-wipes them before any new handshake can repopulate them.
 *
 * The handshake's output is [SessionKeys] (plus peer identity); media and
 * session lifecycle stay in SessionManager.
 */
class HandshakeStateMachine {
    var myKeyPair: KeyPair? = null
    var myNonce = ByteArray(0)
    var myCommitSalt = ByteArray(0)
    var myPubBytes = ByteArray(0)
    var peerPubBytes: ByteArray? = null
    var peerNonce: ByteArray? = null
    var keys: SessionKeys? = null
    var myRole: Byte = 0
    var keyCommitSent = false
    var keyExchangeSent = false
    var keyConfirmSent = false
    var peerKeyCommitment: ByteArray? = null
    var myIdentityId = ByteArray(0)
    var myIdentityPublicKey = ByteArray(0)
    var peerIdentityId: ByteArray? = null
    var peerIdentityPublicKey: ByteArray? = null
    var peerIdentityWasTrusted = false
    var peerReportedIdentityTrusted: Boolean? = null
    var peerHelloReceived = false
    var peerCodecPref: Byte? = null
    var myCodecPref: Byte = CodecId.OPUS

    /**
     * Wipe all key material and identity secrets. SessionManager calls this
     * from its teardown/close paths (previously the inline wipeSecrets()).
     * The ephemeral private key is destroyed here (not just dereferenced) so
     * every abort path — commit/signature mismatch, deadline, user cancel,
     * transport loss, service destroy — gets the same treatment as the
     * success path in deriveAndConfirm().
     */
    fun wipe() {
        keys?.wipe(); keys = null
        // Best-effort destroy of the ephemeral private key; providers that
        // cooperate zero the key material, otherwise the reference is dropped.
        myKeyPair?.let { SessionCrypto.destroyPrivateKey(it.private) }
        myKeyPair = null
        myNonce.fill(0); myNonce = ByteArray(0)
        myCommitSalt.fill(0); myCommitSalt = ByteArray(0)
        myPubBytes.fill(0); myPubBytes = ByteArray(0)
        peerPubBytes?.fill(0); peerPubBytes = null
        peerNonce?.fill(0); peerNonce = null
        peerKeyCommitment?.fill(0); peerKeyCommitment = null
        peerIdentityId?.fill(0); peerIdentityId = null
        peerIdentityPublicKey?.fill(0); peerIdentityPublicKey = null
        peerIdentityWasTrusted = false
        peerReportedIdentityTrusted = null
        myIdentityId.fill(0); myIdentityId = ByteArray(0)
        myIdentityPublicKey.fill(0); myIdentityPublicKey = ByteArray(0)
        keyCommitSent = false; keyExchangeSent = false; keyConfirmSent = false
        peerHelloReceived = false
        peerCodecPref = null
    }

    /** Reset per-attempt flags for a new handshake (keys are wiped separately). */
    fun resetForNewHandshake() {
        keyCommitSent = false
        keyExchangeSent = false
        keyConfirmSent = false
        peerHelloReceived = false
    }

    /**
     * Transcript hash binding this handshake's messages. Moved from
     * SessionManager; pure function of handshake state.
     */
    fun transcriptHash(): ByteArray {
        val peerPub = peerPubBytes ?: error("peer public key absent")
        val peerNc = peerNonce ?: error("peer nonce absent")
        val peerPref = peerCodecPref ?: error("peer HELLO codec preference absent")
        val ownHello = byteArrayOf(Protocol.VERSION, myCodecPref)
        val peerHello = byteArrayOf(Protocol.VERSION, peerPref)
        return try {
            SessionCrypto.transcriptHash(
                myPubBytes, peerPub, myNonce, peerNc, ownHello, peerHello,
            )
        } finally {
            ownHello.fill(0)
            peerHello.fill(0)
        }
    }
}
