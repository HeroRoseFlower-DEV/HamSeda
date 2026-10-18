package com.hamseda.walkie.transport

import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.FramedSocket
import com.hamseda.walkie.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared framed-socket reader loop for stream transports (Bluetooth RFCOMM,
 * Wi-Fi Direct TCP).
 *
 * Extracted from BluetoothTransport and WifiDirectTransport (finding I9):
 * the read/dispatch skeleton was duplicated with only the liveness predicate
 * and the lost/error handlers differing. Subclasses provide those via the
 * abstract hooks; the loop itself — including the HS-04 framing-error path
 * and the stale-frame discard — is identical.
 *
 * This class owns ONLY the reader job. Connection lifecycle, generations,
 * and socket ownership stay in the concrete transports.
 *
 * Generation discipline: [startReader] binds [generation] to THIS reader's
 * coroutine closure. It is never stored in a shared mutable field, so a
 * stale reader that wakes up after cancellation can never act with a newer
 * generation's identity (e.g. tearing down a replacement connection).
 * Transports that additionally guard on socket identity (Bluetooth) may
 * ignore the generation in their hooks; Wi-Fi Direct forwards it.
 */
abstract class AbstractFramedStreamTransport {
    protected abstract val tag: String
    protected abstract val scope: CoroutineScope

    private var readerJob: Job? = null

    /** True while the reader may keep consuming [source] for [generation]. */
    protected abstract fun isReaderActive(source: FramedSocket, generation: Long): Boolean

    /**
     * Handle a received frame. Return false to stop the reader (e.g. the
     * socket was replaced); the frame is already discarded by the caller
     * via [discardStaleFrame] when returning false.
     */
    protected abstract suspend fun onFrame(
        source: FramedSocket,
        generation: Long,
        frame: ByteArray,
    ): Boolean

    /** The peer performed an orderly shutdown. */
    protected abstract suspend fun onClosed(source: FramedSocket, generation: Long)

    /** Read error or framing violation. */
    protected abstract suspend fun onReadError(
        reason: String,
        source: FramedSocket,
        generation: Long,
    )

    /**
     * True if a read [cause] should be ignored (reader continues). Default
     * false; Wi-Fi Direct overrides for its idle socket timeout.
     */
    protected open fun isIgnorableReadError(cause: java.io.IOException): Boolean = false

    /**
     * Start (or restart) the reader for [source], bound to [generation].
     * Must be called with the transport's connection mutex held, matching
     * the previous startReaderLocked() contract.
     *
     * @param idleTimeoutMs when > 0, a watchdog closes [source] after this
     * long without a successful socket read (a peer that dies without FIN
     * would otherwise look alive forever). The threshold must exceed the
     * longest legitimate quiet period — see
     * [com.hamseda.walkie.proto.Protocol.TRANSPORT_IDLE_TIMEOUT_MS].
     */
    protected fun startReader(
        source: FramedSocket,
        generation: Long,
        idleTimeoutMs: Long = 0L,
    ) {
        readerJob?.cancel()
        readerJob = scope.launch {
            if (idleTimeoutMs > 0) {
                // Child of the reader coroutine: cancelled automatically
                // when the reader finishes. Closing the socket unblocks
                // readFrame(); the resulting onClosed/onReadError path
                // validates the per-reader generation before tearing down.
                launch {
                    val startNanos = System.nanoTime()
                    while (true) {
                        delay(idleTimeoutMs / 3)
                        val lastActivity = maxOf(source.lastReadAtNanos, startNanos)
                        val idleMs = (System.nanoTime() - lastActivity) / 1_000_000L
                        if (idleMs > idleTimeoutMs) {
                            AppLog.log(
                                tag,
                                "idle watchdog: no socket read for ${idleMs}ms; " +
                                    "closing possibly half-open socket",
                            )
                            try {
                                source.close()
                            } catch (_: Exception) {}
                            return@launch
                        }
                    }
                }
            }
            while (isReaderActive(source, generation)) {
                // HS-04: malformed frames and I/O failures use the common
                // cleanup path. Always carry the source socket identity AND
                // the per-reader generation so a cancelled old reader cannot
                // tear down a newer connection.
                val r = try {
                    source.readFrame()
                } catch (e: FrameException) {
                    AppLog.log(tag, "framing error: ${e.message}")
                    onReadError("framing error: ${e.message}", source, generation)
                    return@launch
                } catch (e: CancellationException) {
                    throw e // normal shutdown; not an error
                } catch (e: Exception) {
                    AppLog.log(tag, "reader failed: ${e.javaClass.simpleName}")
                    onReadError("read error: ${e.message}", source, generation)
                    return@launch
                }
                when (r) {
                    is FramedSocket.ReadResult.Frame -> {
                        if (!onFrame(source, generation, r.frame)) {
                            return@launch // socket was replaced or closed
                        }
                    }
                    is FramedSocket.ReadResult.Closed -> {
                        onClosed(source, generation)
                        return@launch
                    }
                    is FramedSocket.ReadResult.Error -> {
                        if (isIgnorableReadError(r.cause)) continue
                        onReadError(r.cause.message ?: "read error", source, generation)
                        return@launch
                    }
                }
            }
        }
    }

    protected fun cancelReader() {
        readerJob?.cancel()
        readerJob = null
    }
}
