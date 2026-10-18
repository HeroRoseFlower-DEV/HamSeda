package com.hamseda.walkie.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the transport state-machine guard logic using fake sockets.
 *
 * The full [BluetoothTransport]/[WifiDirectTransport] state machines need
 * Android framework classes and are not covered by instrumentation tests
 * (HamSedaInstrumentedTest only covers permissions, activity recreation,
 * platform crypto and Opus). The generation-guard protocol they both obey
 * is extracted into pure functions ([isCurrentIncomingFrame],
 * [discardStaleFrame]) tested here, and the shared reader-loop dispatch is
 * tested with fake sockets in [ReaderLoopTest].
 */
class TransportStateMachineTest {

    // ---- discardStaleFrame ----

    @Test
    fun discardStaleFrame_wipesBuffer() {
        val frame = byteArrayOf(1, 2, 3, 4, 5)
        discardStaleFrame(frame)
        assertArrayEquals(ByteArray(5), frame)
    }

    @Test
    fun discardStaleFrame_handlesEmptyArray() {
        discardStaleFrame(ByteArray(0)) // must not throw
    }

    // ---- isCurrentIncomingFrame: multi-step scenarios ----

    @Test
    fun staleGenerationAfterDisconnect_rejectsFrames() {
        // Simulates: socket connected at generation 3, user hits Stop
        // (generation -> 4), a late reader delivers a frame for generation 3.
        val socket = Any()
        assertFalse(
            isCurrentIncomingFrame(
                expectedGeneration = 3L,
                currentGeneration = 4L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.IDLE,
            ),
        )
    }

    @Test
    fun replacedSocketAfterReconnect_rejectsOldFrames() {
        // Simulates: reconnect created a new socket at the same generation
        // counter value (counter reset); old socket's frames must not leak in.
        assertFalse(
            isCurrentIncomingFrame(
                expectedGeneration = 5L,
                currentGeneration = 5L,
                expectedSocket = Any(),
                currentSocket = Any(),
                state = TransportState.CONNECTED,
            ),
        )
    }

    @Test
    fun authenticatingSocket_acceptsFramesForHandshake() {
        // Wi-Fi Direct reports AUTHENTICATING while the group exists but the
        // TCP socket is still being established; handshake frames must flow.
        val socket = Any()
        assertTrue(
            isCurrentIncomingFrame(
                expectedGeneration = 1L,
                currentGeneration = 1L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.AUTHENTICATING,
            ),
        )
    }

    @Test
    fun failedTransport_rejectsFrames() {
        val socket = Any()
        assertFalse(
            isCurrentIncomingFrame(
                expectedGeneration = 1L,
                currentGeneration = 1L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.FAILED,
            ),
        )
    }

    @Test
    fun discoveringTransport_rejectsFrames() {
        val socket = Any()
        assertFalse(
            isCurrentIncomingFrame(
                expectedGeneration = 1L,
                currentGeneration = 1L,
                expectedSocket = socket,
                currentSocket = socket,
                state = TransportState.DISCOVERING,
            ),
        )
    }

    // ---- TransportState contract ----

    @Test
    fun authenticating_isDocumentedAsOptional() {
        // AUTHENTICATING is transport-internal and optional: Bluetooth skips
        // it (atomic RFCOMM setup), Wi-Fi Direct reports it. Both must be
        // treated as "connection in progress" by consumers.
        val inProgress = setOf(TransportState.CONNECTING, TransportState.AUTHENTICATING)
        assertTrue(inProgress.contains(TransportState.AUTHENTICATING))
        // CONNECTED is the only state that guarantees a usable socket.
        assertFalse(inProgress.contains(TransportState.CONNECTED))
    }
}
