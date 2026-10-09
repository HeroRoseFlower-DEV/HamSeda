package com.hamseda.walkie.proto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Length-prefixed frame transport over a connected byte stream.
 *
 * Each datagram on the wire is `uint32-be length || frame bytes`.
 * Shared by the Wi-Fi Direct (TCP socket) and Bluetooth Classic (RFCOMM
 * socket stream) transports so framing, size validation, and disconnect
 * detection behave identically on both.
 *
 * Disconnect detection: a clean peer close surfaces as `InputStream.read()`
 * returning -1 (EOF) — this is checked explicitly and reported as
 * [ReadResult.Closed], because some stacks deliver EOF without throwing.
 * Any [IOException] (including [SocketTimeoutException]) is reported as
 * [ReadResult.Error]; the caller decides whether to retry or tear down.
 */
class FramedSocket(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClosed: (() -> Unit)? = null,
) {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    sealed interface ReadResult {
        data class Frame(val frame: ByteArray) : ReadResult
        /** Peer performed an orderly shutdown (read() returned -1). */
        data object Closed : ReadResult
        data class Error(val cause: IOException) : ReadResult
    }

    /** Sends one frame (already-encoded [Frame] bytes). Thread-safe. */
    @Throws(IOException::class)
    fun writeFrame(frameBytes: ByteArray) {
        if (closed.get()) throw IOException("socket closed")
        require(frameBytes.size <= Protocol.MAX_FRAME) {
            "frame too large: ${frameBytes.size}"
        }
        val envelope = ByteBuffer.allocate(4 + frameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        envelope.putInt(frameBytes.size)
        envelope.put(frameBytes)
        synchronized(writeLock) {
            if (closed.get()) throw IOException("socket closed")
            output.write(envelope.array())
            output.flush()
        }
    }

    /**
     * Reads one length-prefixed frame.
     * Returns [ReadResult.Closed] on clean EOF, [ReadResult.Error] on I/O
     * failure, and throws [FrameException] for malformed/oversized envelopes
     * (protocol violation — the caller should drop the connection).
     */
    fun readFrame(): ReadResult {
        if (closed.get()) return ReadResult.Closed
        return try {
            val lenBytes = readExactly(4) ?: return ReadResult.Closed
            val length = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
            if (length <= 0 || length > Protocol.MAX_FRAME) {
                throw FrameException("envelope length out of bounds: $length")
            }
            val frame = readExactly(length) ?: return ReadResult.Closed
            ReadResult.Frame(frame)
        } catch (e: FrameException) {
            throw e
        } catch (e: IOException) {
            ReadResult.Error(e)
        }
    }

    /**
     * Reads exactly [n] bytes, or returns null if EOF is hit before any byte
     * could be read at the current position. A mid-frame EOF throws
     * [EOFException] (truncated peer stream = protocol error).
     */
    private fun readExactly(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) {
                if (off == 0) return null // clean EOF at a frame boundary
                throw EOFException("truncated frame: got $off of $n bytes before EOF")
            }
            off += r
        }
        return buf
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { input.close() } catch (_: IOException) {}
            try { output.close() } catch (_: IOException) {}
            try { onClosed?.invoke() } catch (_: Exception) {}
        }
    }

    companion object {
        /** Wraps a connected TCP [Socket] with the configured timeouts. */
        fun fromTcpSocket(socket: Socket, onClosed: (() -> Unit)? = null): FramedSocket {
            socket.soTimeout = Protocol.SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            return FramedSocket(socket.inputStream, socket.outputStream, onClosed)
        }

        /**
         * Wraps Bluetooth RFCOMM streams. RFCOMM streams do not support
         * SO_TIMEOUT; callers should read on a dedicated thread and close
         * the socket to unblock a pending read.
         */
        fun fromStreams(input: InputStream, output: OutputStream, onClosed: (() -> Unit)? = null) =
            FramedSocket(input, output, onClosed)
    }

}
