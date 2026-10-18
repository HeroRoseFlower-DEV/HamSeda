package com.hamseda.walkie.transport

import com.hamseda.walkie.proto.FramedSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shared reader-loop dispatch in
 * [AbstractFramedStreamTransport], driven with fake [FramedSocket]s backed
 * by in-memory streams.
 *
 * Covers every dispatch branch of [AbstractFramedStreamTransport.startReader]
 * — Frame, Closed, Error, framing violation, ignorable error — and the
 * per-reader generation binding: each hook must receive the generation that
 * was bound when ITS reader started, never a shared mutable field (the
 * regression fixed after the 2026-10-17 audit).
 */
class ReaderLoopTest {

    private class FakeReaderTransport(
        override val scope: CoroutineScope,
    ) : AbstractFramedStreamTransport() {
        override val tag = "ReaderLoopTest"
        val receivedFrames = mutableListOf<Pair<Long, ByteArray>>()
        val closedGenerations = mutableListOf<Long>()
        val errorReports = mutableListOf<Triple<String, Long, FramedSocket>>()
        var frameResult = true
        var active = true
        var ignorable: (IOException) -> Boolean = { false }

        override fun isReaderActive(source: FramedSocket, generation: Long) = active

        override suspend fun onFrame(
            source: FramedSocket,
            generation: Long,
            frame: ByteArray,
        ): Boolean {
            receivedFrames.add(generation to frame)
            return frameResult
        }

        override suspend fun onClosed(source: FramedSocket, generation: Long) {
            closedGenerations.add(generation)
        }

        override suspend fun onReadError(
            reason: String,
            source: FramedSocket,
            generation: Long,
        ) {
            errorReports.add(Triple(reason, generation, source))
        }

        override fun isIgnorableReadError(cause: IOException) = ignorable(cause)

        fun start(source: FramedSocket, generation: Long, idleTimeoutMs: Long = 0L) =
            startReader(source, generation, idleTimeoutMs)

        fun stop() = cancelReader()
    }

    private fun frameBytes(vararg payload: Byte): ByteArray {
        val buf = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    private fun socketOf(vararg chunks: ByteArray): FramedSocket {
        val combined = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        return FramedSocket(ByteArrayInputStream(combined), ByteArrayOutputStream())
    }

    private suspend fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                error("condition not met within ${timeoutMs}ms")
            }
            delay(10)
        }
    }

    private fun awaitTrueBlocking(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                error("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun frameDispatch_deliversFrameWithGeneration() = runTest {
        val t = FakeReaderTransport(this)
        val socket = socketOf(frameBytes(1, 2, 3))
        t.start(socket, generation = 7L)
        awaitTrue { t.receivedFrames.isNotEmpty() }
        assertEquals(7L, t.receivedFrames[0].first)
        assertEquals(listOf<Byte>(1, 2, 3), t.receivedFrames[0].second.toList())
        t.stop()
    }

    @Test
    fun closedDispatch_reportsGeneration() = runTest {
        val t = FakeReaderTransport(this)
        val socket = socketOf() // immediate EOF
        t.start(socket, generation = 9L)
        awaitTrue { t.closedGenerations.isNotEmpty() }
        assertEquals(listOf(9L), t.closedGenerations)
        t.stop()
    }

    @Test
    fun ioErrorDispatch_reportsReadErrorWithGeneration() = runTest {
        val t = FakeReaderTransport(this)
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw IOException("boom")
        }
        val socket = FramedSocket(failing, ByteArrayOutputStream())
        t.start(socket, generation = 3L)
        awaitTrue { t.errorReports.isNotEmpty() }
        assertEquals(3L, t.errorReports[0].second)
        assertTrue(t.errorReports[0].first.contains("boom"))
        t.stop()
    }

    @Test
    fun framingErrorDispatch_reportsFramingError() = runTest {
        val t = FakeReaderTransport(this)
        // Zero-length envelope -> FrameException ("out of bounds").
        val socket = socketOf(frameBytes())
        t.start(socket, generation = 5L)
        awaitTrue { t.errorReports.isNotEmpty() }
        assertEquals(5L, t.errorReports[0].second)
        assertTrue(t.errorReports[0].first.startsWith("framing error"))
        t.stop()
    }

    @Test
    fun onFrameFalse_stopsReader() = runTest {
        val t = FakeReaderTransport(this)
        t.frameResult = false
        val socket = socketOf(frameBytes(1), frameBytes(2))
        t.start(socket, generation = 1L)
        awaitTrue { t.receivedFrames.isNotEmpty() }
        // Give the loop a chance to (incorrectly) read a second frame.
        delay(200)
        assertEquals(1, t.receivedFrames.size)
        t.stop()
    }

    @Test
    fun ignorableError_continuesReading() = runTest {
        val t = FakeReaderTransport(this)
        t.ignorable = { it is SocketTimeoutException }
        var reads = 0
        val flaky = object : InputStream() {
            private val data = frameBytes(9, 9)
            private var pos = 0
            override fun read(): Int = throw AssertionError("3-arg read expected")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads == 0) {
                    reads++
                    throw SocketTimeoutException("timed out")
                }
                if (pos >= data.size) return -1
                val n = minOf(len, data.size - pos)
                data.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        val socket = FramedSocket(flaky, ByteArrayOutputStream())
        t.start(socket, generation = 2L)
        awaitTrue { t.receivedFrames.isNotEmpty() }
        assertEquals(2L, t.receivedFrames[0].first)
        assertTrue(t.errorReports.isEmpty()) // ignorable: no error reported
        t.stop()
    }

    @Test
    fun perReaderGeneration_eachHookGetsItsOwnGeneration() = runTest {
        // Sequential readers: each hook must report the generation bound at
        // ITS reader's start, never a shared mutable field.
        val t = FakeReaderTransport(this)
        t.start(socketOf(), generation = 11L)
        awaitTrue { t.closedGenerations.isNotEmpty() }
        t.start(socketOf(), generation = 12L)
        awaitTrue { t.closedGenerations.size >= 2 }
        assertEquals(listOf(11L, 12L), t.closedGenerations)
        t.stop()
    }

    @Test
    fun staleReader_reportsOwnGenerationNotTheNewOne() {
        // A reader blocked in readFrame() is cancelled by a reconnect, then
        // wakes up late. It must report ITS generation (21) so the
        // generation guard drops it — never the new reader's (22).
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val blocking = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
                return -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                gate.await(10, TimeUnit.SECONDS)
                return -1
            }
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val t = FakeReaderTransport(scope)
            t.start(FramedSocket(blocking, ByteArrayOutputStream()), generation = 21L)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            t.start(socketOf(), generation = 22L) // cancels the stale reader's job
            awaitTrueBlocking { t.closedGenerations.contains(22L) }
            gate.countDown() // release the stale reader
            awaitTrueBlocking { t.closedGenerations.size >= 2 }
            assertTrue(
                "stale reader must report its own generation",
                t.closedGenerations.contains(21L),
            )
            assertEquals(1, t.closedGenerations.count { it == 22L })
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun idleWatchdog_closesSilentSocket() {
        // A socket that never yields a read is closed after the idle
        // timeout; the reader then reports Closed with its generation.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val t = FakeReaderTransport(scope)
            val never = object : InputStream() {
                @Volatile var closed = false
                override fun read(): Int = read(ByteArray(1), 0, 1)
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    // Block until close() (as the watchdog does), then EOF.
                    while (!closed) Thread.sleep(50)
                    return -1
                }
                override fun close() {
                    closed = true
                }
            }
            t.start(FramedSocket(never, ByteArrayOutputStream()), generation = 4L, idleTimeoutMs = 300L)
            awaitTrueBlocking(timeoutMs = 10_000) { t.closedGenerations.isNotEmpty() }
            assertEquals(listOf(4L), t.closedGenerations)
            t.stop()
        } finally {
            scope.cancel()
        }
    }
}
