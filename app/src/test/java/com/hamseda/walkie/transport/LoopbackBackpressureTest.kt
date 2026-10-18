package com.hamseda.walkie.transport

import com.hamseda.walkie.LoopbackTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val sendStarted = CountDownLatch(1)
        val sendDone = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            sendStarted.countDown()
            a.sendFrame(frame)
            sendDone.countDown()
        }
        assertTrue("sender started", sendStarted.await(5, TimeUnit.SECONDS))
        assertFalse(
            "send must block while nobody collects (backpressure)",
            sendDone.await(300, TimeUnit.MILLISECONDS),
        )

        // Start collecting: the blocked send must now complete and deliver.
        val received = CountDownLatch(1)
        var got: ByteArray? = null
        val collector = launch(Dispatchers.IO) {
            b.incomingFrames.collect {
                got = it
                received.countDown()
            }
        }
        assertTrue("blocked send completed once collected", sendDone.await(5, TimeUnit.SECONDS))
        assertTrue("frame delivered", received.await(5, TimeUnit.SECONDS))
        assertArrayEquals(frame, got)

        job.cancel()
        collector.cancel()
    }

    @Test
    fun `buffered loopback still drains without a live consumer`() = runTest {
        // The default buffered mode keeps existing tests' fire-and-forget
        // behavior: sends complete even if nobody is collecting yet.
        val (a, b) = LoopbackTransport.pair(bufferCapacity = 256)
        val frame = ByteArray(8) { 7 }
        a.sendFrame(frame) // must not suspend
        val received = CountDownLatch(1)
        var got: ByteArray? = null
        val collector = launch(Dispatchers.IO) {
            b.incomingFrames.collect {
                got = it
                received.countDown()
            }
        }
        assertTrue("frame delivered", received.await(5, TimeUnit.SECONDS))
        assertArrayEquals(frame, got)
        collector.cancel()
    }
}
