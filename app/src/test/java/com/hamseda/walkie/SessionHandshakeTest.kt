package com.hamseda.walkie

import com.hamseda.walkie.audio.OpusCodec
import com.hamseda.walkie.proto.Frame
import com.hamseda.walkie.proto.MessageType
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.session.FloorController
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.transport.PeerDevice
import com.hamseda.walkie.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end session tests over the in-memory loopback transport pair:
 * handshake, SAS confirmation, floor control, and live audio frames —
 * plus MITM and mismatch rejection.
 */
class SessionHandshakeTest {

    private data class Rig(
        val a: SessionManager,
        val b: SessionManager,
        val audioA: FakeAudioPipeline,
        val audioB: FakeAudioPipeline,
        val ta: LoopbackTransport,
        val tb: LoopbackTransport,
        val scopeA: CoroutineScope,
        val scopeB: CoroutineScope,
    )

    private fun newRig(
        interceptA: ((ByteArray) -> ByteArray)? = null,
        interceptB: ((ByteArray) -> ByteArray)? = null,
    ): Rig {
        val (ta, tb) = LoopbackTransport.pair(interceptA, interceptB)
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val audioA = FakeAudioPipeline()
        val audioB = FakeAudioPipeline()
        return Rig(
            SessionManager(scopeA, audioA), SessionManager(scopeB, audioB),
            audioA, audioB, ta, tb, scopeA, scopeB,
        )
    }

    private fun Rig.start() {
        a.startOutgoing(ta, PeerDevice("b", "B", TransportType.WIFI_DIRECT))
        b.acceptIncoming(tb)
    }

    private fun Rig.close() {
        a.close(); b.close()
        scopeA.cancel(); scopeB.cancel()
    }

    private fun Rig.await(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun `key exchange before HELLO is rejected`() {
        val wrongOrder = newRig(interceptB = { bytes ->
            val frame = Frame.decode(bytes)
            if (frame.type == MessageType.HELLO) {
                frame.copy(type = MessageType.KEY_EXCHANGE).encode()
            } else {
                bytes
            }
        })
        wrongOrder.start()
        wrongOrder.await("KEY_EXCHANGE before HELLO rejected") {
            wrongOrder.a.phase.value == SessionManager.Phase.IDLE &&
                wrongOrder.a.error.value == SessionManager.SessionError.PROTOCOL_ERROR
        }
        wrongOrder.close()
    }

    @Test
    fun `unsupported HELLO codec is rejected`() {
        val badCodec = newRig(interceptB = { bytes ->
            val frame = Frame.decode(bytes)
            if (frame.type == MessageType.HELLO && frame.payload.size == 2) {
                frame.copy(payload = frame.payload.copyOf().apply { this[1] = 0x7F.toByte() }).encode()
            } else {
                bytes
            }
        })
        badCodec.start()
        badCodec.await("unsupported HELLO codec rejected") {
            badCodec.a.phase.value == SessionManager.Phase.IDLE &&
                badCodec.a.error.value == SessionManager.SessionError.PROTOCOL_ERROR
        }
        badCodec.close()
    }

    @Test
    fun `full handshake reaches IN_SESSION with matching SAS`() {
        val rig = newRig()
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM &&
                rig.b.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        val sasA = rig.a.sasCode.value
        val sasB = rig.b.sasCode.value
        assertNotNull(sasA)
        assertEquals(sasA, sasB)
        assertEquals(6, sasA!!.length)

        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.a.phase.value == SessionManager.Phase.IN_SESSION &&
                rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }
        // Playback path is armed on both sides.
        assertNotNull(rig.audioA.playbackBuffer)
        assertNotNull(rig.audioB.playbackBuffer)
        rig.close()
    }

    @Test
    fun `ptt grants floor and audio frames reach the peer`() {
        val rig = newRig()
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.a.phase.value == SessionManager.Phase.IN_SESSION &&
                rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }

        rig.a.setPttPressed(true)
        rig.await("transmitting") { rig.audioA.isCapturing }
        rig.await("peer holds floor") {
            rig.b.floorHolder.value == FloorController.Holder.PEER
        }

        // Drive three encoded frames through A's capture callback.
        val opus = OpusCodec()
        val pcm = ShortArray(320) { (it % 100).toShort() }
        val encoded = opus.encode(pcm)
        repeat(3) { rig.audioA.onFrame?.invoke(encoded, 0.5f) }
        rig.await("audio arrived") {
            (rig.audioB.playbackBuffer?.bufferedFrames() ?: 0) >= 1
        }

        rig.a.setPttPressed(false)
        rig.await("capture stopped") { !rig.audioA.isCapturing }
        rig.await("floor free") {
            rig.b.floorHolder.value == FloorController.Holder.NONE
        }
        opus.close()
        rig.close()
    }

    @Test
    fun `sas mismatch aborts the session`() {
        val rig = newRig()
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(false) // codes differ!
        rig.await("a aborted") {
            rig.a.phase.value == SessionManager.Phase.IDLE &&
                rig.a.error.value == SessionManager.SessionError.AUTH_MISMATCH
        }
        rig.close()
    }

    @Test
    fun `mitm tampering with key exchange is detected`() {
        // Flip a byte inside A's KEY_EXCHANGE nonce in flight: both sides
        // still derive *valid* keys, but different ones — the sealed
        // KEY_CONFIRM transcript check must then fail.
        val tamper: (ByteArray) -> ByteArray = { bytes ->
            val frame = Frame.decode(bytes)
            if (frame.type == MessageType.KEY_EXCHANGE) {
                val out = bytes.copyOf()
                // payload = pubLen(2) + pub(91) + nonce(16); flip first nonce byte
                val nonceAt = Protocol.HEADER_SIZE + 2 + 91
                out[nonceAt] = (out[nonceAt].toInt() xor 0xFF).toByte()
                out
            } else bytes
        }
        val rig = newRig(interceptA = tamper)
        rig.start()
        rig.await("a detects mitm") {
            rig.a.phase.value == SessionManager.Phase.IDLE &&
                rig.a.error.value == SessionManager.SessionError.AUTH_MISMATCH
        }
        // The session never reaches the SAS phase, let alone audio.
        assertNull(rig.a.sasCode.value)
        assertTrue(rig.a.phase.value != SessionManager.Phase.IN_SESSION)
        rig.close()
    }

    @Test
    fun `abrupt transport loss ends the session`() {
        val rig = newRig()
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.a.phase.value == SessionManager.Phase.IN_SESSION
        }
        // Simulate the radio dropping mid-call.
        kotlinx.coroutines.runBlocking { rig.ta.disconnect() }
        rig.await("session torn down") {
            rig.a.phase.value == SessionManager.Phase.IDLE &&
                rig.a.error.value == SessionManager.SessionError.TRANSPORT_LOST
        }
        assertNull(rig.audioA.playbackBuffer)
        rig.close()
    }

    @Test
    fun `replayed wire frames are rejected end-to-end`() {
        var lastAudioWire: ByteArray? = null
        val rig = newRig(interceptA = { bytes ->
            if (decodeType(bytes) == MessageType.AUDIO) lastAudioWire = bytes
            bytes
        })
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.a.phase.value == SessionManager.Phase.IN_SESSION &&
                rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }
        rig.a.setPttPressed(true)
        rig.await("transmitting") { rig.audioA.isCapturing }
        val opus = OpusCodec()
        rig.audioA.onFrame?.invoke(opus.encode(ShortArray(320) { 7 }), 0.5f)
        rig.await("wire frame captured") { lastAudioWire != null }

        val failuresBefore = rig.b.authFailures.value
        // Attacker replays the exact wire bytes: B's replay window must drop it.
        kotlinx.coroutines.runBlocking { rig.ta.sendFrame(lastAudioWire!!) }
        rig.await("replay rejected") { rig.b.authFailures.value > failuresBefore }
        // ...and the session is unaffected.
        assertEquals(SessionManager.Phase.IN_SESSION, rig.b.phase.value)
        opus.close()
        rig.close()
    }
}
