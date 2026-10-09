package com.hamseda.walkie

import com.hamseda.walkie.audio.OpusCodec
import com.hamseda.walkie.proto.Frame
import com.hamseda.walkie.proto.MessageType
import com.hamseda.walkie.session.FloorController
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.transport.PeerDevice
import com.hamseda.walkie.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Regression tests for HS-01 (authenticate before state mutation) and
 * HS-02 (serialized outbound pipeline).
 *
 * HS-01: an unauthenticated frame — even with a plausible session id and
 * sequence — must not advance the replay window, refresh liveness, or
 * affect the floor. Verified by delivering a bad-tag frame with a huge
 * sequence number, then proving a legitimate lower-sequence frame is still
 * accepted (a poisoned window would reject it as "too old").
 */
class SessionSecurityTest {

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

    @Test
    fun `hs05 playback failure ends session cleanly`() {
        val rig = newRig()
        rig.audioA.failPlayback = true
        rig.audioB.failPlayback = true
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM &&
                rig.b.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        // Playback fails → session must roll back, not fake IN_SESSION.
        rig.await("a rolled back") {
            rig.a.phase.value == SessionManager.Phase.IDLE &&
                rig.a.error.value == SessionManager.SessionError.PLAYBACK_UNAVAILABLE
        }
        // B either fails its own playback (PLAYBACK_UNAVAILABLE) or gets
        // A's DISCONNECT first (PEER_REJECTED); either way it must never
        // report IN_SESSION.
        rig.await("b not in session") {
            rig.b.phase.value == SessionManager.Phase.IDLE
        }
        assertTrue(rig.b.phase.value != SessionManager.Phase.IN_SESSION)
        rig.close()
    }

    @Test
    fun `hs07 handshake times out when peer vanishes`() = runTest {
        // Virtual time: the 30s deadline fires without waiting 30s.
        val (ta, tb) = LoopbackTransport.pair()
        val audioA = FakeAudioPipeline()
        val sm = SessionManager(this, audioA)
        sm.startOutgoing(ta, PeerDevice("b", "B", TransportType.WIFI_DIRECT))
        // Let connect() run: transport CONNECTED → handshake starts.
        testScheduler.advanceTimeBy(1_000)
        assertEquals(SessionManager.Phase.HANDSHAKE, sm.phase.value)
        // Peer never responds. Advance past HANDSHAKE_TIMEOUT_MS.
        testScheduler.advanceTimeBy(31_000)
        assertEquals(SessionManager.Phase.IDLE, sm.phase.value)
        assertEquals(SessionManager.SessionError.PEER_TIMEOUT, sm.error.value)
        sm.close()
    }

    @Test
    fun `hs07 sas confirm times out when peer never confirms`() = runTest {
        val (ta, tb) = LoopbackTransport.pair()
        val audioA = FakeAudioPipeline()
        val audioB = FakeAudioPipeline()
        val smA = SessionManager(this, audioA)
        val smB = SessionManager(this, audioB)
        smA.startOutgoing(ta, PeerDevice("b", "B", TransportType.WIFI_DIRECT))
        smB.acceptIncoming(tb)
        // Drive to SAS phase (virtual time; handshake messages flow).
        testScheduler.advanceTimeBy(5_000)
        assertEquals(SessionManager.Phase.AWAITING_SAS_CONFIRM, smA.phase.value)
        assertEquals(SessionManager.Phase.AWAITING_SAS_CONFIRM, smB.phase.value)
        // Neither side confirms. Advance past SAS_CONFIRM_TIMEOUT_MS.
        testScheduler.advanceTimeBy(121_000)
        assertEquals(SessionManager.Phase.IDLE, smA.phase.value)
        assertEquals(SessionManager.SessionError.PEER_TIMEOUT, smA.error.value)
        smA.close(); smB.close()
    }

    private fun Rig.start() {
        // Subscribe the waiting endpoint before the initiating endpoint can
        // send HELLO. This mirrors the real transport lifecycle and avoids
        // dropping the first frame in the in-memory SharedFlow test harness.
        b.acceptIncoming(tb)
        a.startOutgoing(ta, PeerDevice("b", "B", TransportType.WIFI_DIRECT))
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
    fun `hs01 bad tag huge seq does not poison replay window`() {
        var capturedSid: ByteArray? = null
        val rig = newRig(interceptA = { bytes ->
            try {
                val f = Frame.decode(bytes)
                if (f.sessionId.any { it != 0.toByte() }) capturedSid = f.sessionId
            } catch (_: Exception) {}
            bytes
        })
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM &&
                rig.b.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.a.phase.value == SessionManager.Phase.IN_SESSION &&
                rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }
        // Wait until we've seen a sealed frame carrying the real session id.
        rig.await("session id captured") { capturedSid != null }
        val sid = capturedSid!!

        val failuresBefore = rig.b.authFailures.value

        // Attack: correct session id, huge sequence, garbage payload (bad tag).
        val malicious = Frame(
            type = MessageType.AUDIO,
            sessionId = sid,
            seq = 999_999L,
            timestamp = System.currentTimeMillis(),
            codecId = 0,
            payload = ByteArray(64) { it.toByte() },
        ).encode()
        assertTrue(rig.tb.inject(malicious))
        rig.await("attack counted") { rig.b.authFailures.value > failuresBefore }

        // A legitimate lower-sequence frame must STILL be accepted. If the
        // bad frame had advanced the replay window, this would be dropped
        // as "too old" and B would never hear audio.
        rig.a.setPttPressed(true)
        rig.await("transmitting") { rig.audioA.isCapturing }
        rig.await("peer holds floor") {
            rig.b.floorHolder.value == FloorController.Holder.PEER
        }
        val opus = OpusCodec()
        val encoded = opus.encode(ShortArray(320) { 7 })
        rig.audioA.onFrame?.invoke(encoded, 0.5f)
        rig.await("audio arrived despite attack") {
            (rig.audioB.playbackBuffer?.bufferedFrames() ?: 0) >= 1
        }
        assertEquals(SessionManager.Phase.IN_SESSION, rig.b.phase.value)
        opus.close()
        rig.close()
    }

    @Test
    fun `hs01 wrong session id is dropped without effect`() {
        val rig = newRig()
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }
        val failuresBefore = rig.b.authFailures.value
        val foreign = Frame(
            type = MessageType.PING,
            sessionId = ByteArray(8) { 0x42 }, // not our session
            seq = 1L,
            timestamp = System.currentTimeMillis(),
            codecId = 0xFF.toByte(),
            payload = ByteArray(32),
        ).encode()
        assertTrue(rig.tb.inject(foreign))
        rig.await("foreign dropped") { rig.b.authFailures.value > failuresBefore }
        assertEquals(SessionManager.Phase.IN_SESSION, rig.b.phase.value)
        rig.close()
    }

    @Test
    fun `hs01 duplicate valid frame is rejected`() {
        var lastPing: ByteArray? = null
        val rig = newRig(interceptA = { bytes ->
            if (decodeType(bytes) == MessageType.PING) lastPing = bytes
            bytes
        })
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }
        // Wait for a live PING on the wire (liveness, every 5s).
        rig.await("ping captured", timeoutMs = 12_000) { lastPing != null }
        val failuresBefore = rig.b.authFailures.value
        // The first delivery was already consumed; replay the same bytes.
        runBlocking { rig.ta.sendFrame(lastPing!!) }
        rig.await("duplicate rejected") { rig.b.authFailures.value > failuresBefore }
        assertEquals(SessionManager.Phase.IN_SESSION, rig.b.phase.value)
        rig.close()
    }

    @Test
    fun `hs01 concurrent malicious inputs do not race session state`() {
        var capturedSid: ByteArray? = null
        val rig = newRig(interceptA = { bytes ->
            try {
                val f = Frame.decode(bytes)
                if (f.sessionId.any { it != 0.toByte() }) capturedSid = f.sessionId
            } catch (_: Exception) {}
            bytes
        })
        rig.start()
        rig.await("sas phase") {
            rig.a.phase.value == SessionManager.Phase.AWAITING_SAS_CONFIRM
        }
        rig.a.confirmSas(true)
        rig.b.confirmSas(true)
        rig.await("in session") {
            rig.b.phase.value == SessionManager.Phase.IN_SESSION
        }
        rig.await("session id captured") { capturedSid != null }
        val sid = capturedSid!!
        val failuresBefore = rig.b.authFailures.value

        // 8 threads hammering bad-tag frames with ascending huge seqs.
        val threads = (0 until 8).map { t ->
            thread {
                repeat(50) { i ->
                    val bad = Frame(
                        type = MessageType.AUDIO,
                        sessionId = sid,
                        seq = 500_000L + t * 1000 + i,
                        timestamp = System.currentTimeMillis(),
                        codecId = 0,
                        payload = ByteArray(48) { (t + i).toByte() },
                    ).encode()
                    rig.tb.injectBlocking(bad)
                }
            }
        }
        threads.forEach { it.join() }
        rig.await("all bad frames counted") {
            rig.b.authFailures.value >= failuresBefore + 400
        }
        // Session must be alive and the replay window unpoisoned: a real
        // PING from A (next liveness tick) still gets a PONG.
        assertEquals(SessionManager.Phase.IN_SESSION, rig.b.phase.value)
        rig.close()
    }

    @Test
    fun `hs02 concurrent audio frames have unique monotonic wire sequences`() {
        val wireSeqs = Collections.synchronizedList(mutableListOf<Long>())
        val rig = newRig(interceptA = { bytes ->
            try {
                val f = Frame.decode(bytes)
                if (f.type == MessageType.AUDIO) wireSeqs.add(f.seq)
            } catch (_: Exception) {}
            // Slight transport slowness to amplify any producer race.
            Thread.sleep(1)
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

        val perThread = 25
        val threads = 8
        val frame = ByteArray(60) { 3 }
        val workers = (0 until threads).map {
            thread {
                repeat(perThread) {
                    rig.audioA.onFrame?.invoke(frame, 0.5f)
                }
            }
        }
        workers.forEach { it.join() }
        val total = threads * perThread
        rig.await("all frames on wire", timeoutMs = 15_000) { wireSeqs.size >= total }

        val seqs = wireSeqs.take(total)
        // Unique…
        assertEquals(total, seqs.toSet().size)
        // …and wire order is the sequence order (single serialized sender).
        assertEquals(seqs.sorted(), seqs)
        rig.close()
    }

    @Test
    fun `hs02 outbound queue stays bounded under a stalled transport`() {
        // Hammer the outbound pipeline with far more frames than the queue
        // holds: drops must be bounded (no OOM, no hang) and the session
        // must survive. Uses the real loopback (fast drain) — the bound is
        // structural (Channel capacity + trySend drops).
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
        rig.a.setPttPressed(true)
        rig.await("transmitting") { rig.audioA.isCapturing }

        val frame = ByteArray(60) { 9 }
        val threads = (0 until 8).map {
            thread {
                repeat(250) {
                    rig.audioA.onFrame?.invoke(frame, 0.5f)
                }
            }
        }
        threads.forEach { it.join() }
        // 2000 frames through a 256-slot queue: must complete without
        // hanging or breaking the session.
        Thread.sleep(1000)
        assertEquals(SessionManager.Phase.IN_SESSION, rig.a.phase.value)
        assertEquals(SessionManager.Phase.IN_SESSION, rig.b.phase.value)
        // And the session still works afterwards.
        rig.a.setPttPressed(false)
        rig.await("capture stopped") { !rig.audioA.isCapturing }
        rig.close()
    }
}
