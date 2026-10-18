package com.hamseda.walkie.transport

import com.hamseda.walkie.LoopbackTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the LoopbackTransport backpressure contract. Production
 * transports (Bluetooth RFCOMM, Wi-Fi Direct sockets) are unbuffered: a slow
 * reader backpressures the sender instead of queueing stale voice. The fake
 * defaults to a buffer for test convenience, but bufferCapacity = 0 must
 * reproduce the production rendezvous behavior — otherwise no test can catch
 * producer/consumer bugs that only appear under backpressure.
 */
class LoopbackBackpressureTest {

    @Test
    fun `unbuffered loopback blocks sender until consumer collects`() = runTest {
        val (a, b) = LoopbackTransport.pair(bufferCapacity = 0)
        val frame = ByteArray(16) { it.toByte() }

        // No collector on b: the send must block (backpressure), not buffer.
        // launch is undispatched in runTest: runs until first suspension.
        var sendCompleted = false
        val sendJob = launch {
            a.sendFrame(frame)
            sendCompleted = true
        }
        runCurrent()
        assertFalse("send must block while nobody collects (backpressure)", sendCompleted)

        // Start collecting: the blocked send must now complete and deliver.
        var got: ByteArray? = null
        val collectJob = launch {
            b.incomingFrames.collect { got = it }
        }
        runCurrent()
        assertTrue("blocked send completed once collected", sendCompleted)
        assertArrayEquals(frame, got)

        sendJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun `buffered loopback still drains without a live consumer`() = runTest {
        // The default buffered mode keeps existing tests' fire-and-forget
        // behavior: sends complete even if nobody is collecting yet.
        val (a, b) = LoopbackTransport.pair(bufferCapacity = 256)
        val frame = ByteArray(8) { 7 }

        var sendCompleted = false
        launch {
            a.sendFrame(frame)
            sendCompleted = true
        }
        runCurrent()
        assertTrue("buffered send must not block", sendCompleted)

        var got: ByteArray? = null
        val collectJob = launch {
            b.incomingFrames.collect { got = it }
        }
        runCurrent()
        assertArrayEquals(frame, got)
        collectJob.cancel()
    }
}
