package com.hamseda.walkie

import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.FramedSocket
import com.hamseda.walkie.proto.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class FramedSocketTest {

    private fun pipe(): Triple<FramedSocket, FramedSocket, PipedOutputStream> {
        // a -> b direction
        val aOut = PipedOutputStream()
        val bIn = PipedInputStream(aOut)
        val a = FramedSocket.fromStreams(object : java.io.InputStream() {
            override fun read(): Int = -1 // a never reads
        }, aOut)
        val b = FramedSocket.fromStreams(bIn, object : java.io.OutputStream() {
            override fun write(b: Int) {}
        })
        return Triple(a, b, aOut)
    }

    @Test
    fun `write then read delivers the exact frame`() {
        val (a, b, _) = pipe()
        val payload = ByteArray(100) { it.toByte() }
        a.writeFrame(payload)
        val r = b.readFrame()
        assertTrue(r is FramedSocket.ReadResult.Frame)
        assertArrayEquals(payload, (r as FramedSocket.ReadResult.Frame).frame)
        a.close(); b.close()
    }

    @Test
    fun `multiple frames stay delimited`() {
        val (a, b, _) = pipe()
        repeat(5) { i -> a.writeFrame(byteArrayOf(i.toByte(), 1, 2, 3)) }
        repeat(5) { i ->
            val r = b.readFrame()
            assertTrue(r is FramedSocket.ReadResult.Frame)
            assertArrayEquals(
                byteArrayOf(i.toByte(), 1, 2, 3),
                (r as FramedSocket.ReadResult.Frame).frame,
            )
        }
        a.close(); b.close()
    }

    @Test
    fun `clean EOF is reported as Closed not an exception`() {
        val (a, b, aOut) = pipe()
        aOut.close() // peer closed its end
        val r = b.readFrame()
        assertTrue(r is FramedSocket.ReadResult.Closed)
        a.close(); b.close()
    }

    @Test
    fun `oversized envelope length is rejected`() {
        val rawOut = PipedOutputStream()
        val rawIn = PipedInputStream(rawOut)
        val b = FramedSocket.fromStreams(rawIn, object : java.io.OutputStream() {
            override fun write(x: Int) {}
        })
        // Envelope claims more than MAX_FRAME bytes.
        val evil = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F.toByte())
        rawOut.write(evil)
        rawOut.flush()
        assertThrows(FrameException::class.java) { b.readFrame() }
        b.close()
    }

    @Test
    fun `truncated frame surfaces as Error`() {
        val rawOut = PipedOutputStream()
        val rawIn = PipedInputStream(rawOut)
        val b = FramedSocket.fromStreams(rawIn, object : java.io.OutputStream() {
            override fun write(x: Int) {}
        })
        rawOut.write(byteArrayOf(0, 0, 0, 50)) // claims 50 bytes
        rawOut.write(ByteArray(10)) // delivers only 10, then EOF
        rawOut.close()
        val r = b.readFrame()
        assertTrue(r is FramedSocket.ReadResult.Error)
        b.close()
    }

    @Test
    fun `oversized write is rejected`() {
        val (a, b, _) = pipe()
        assertThrows(IllegalArgumentException::class.java) {
            a.writeFrame(ByteArray(Protocol.MAX_FRAME + 1))
        }
        a.close(); b.close()
    }
}
