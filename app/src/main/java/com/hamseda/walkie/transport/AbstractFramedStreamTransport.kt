package com.hamseda.walkie.transport

import com.hamseda.walkie.proto.FrameException
import com.hamseda.walkie.proto.FramedSocket
import com.hamseda.walkie.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 */
abstract class AbstractFramedStreamTransport {
    protected abstract val tag: String
    protected abstract val scope: CoroutineScope

    private var readerJob: Job? = null

    /** True while the reader may keep consuming [source]. */
    protected abstract fun isReaderActive(source: FramedSocket): Boolean

    /**
     * Handle a received frame. Return false to stop the reader (e.g. the
     * socket was replaced); the frame is already discarded by the caller
     * via [discardStaleFrame] when returning false.
     */
    protected abstract suspend fun onFrame(source: FramedSocket, frame: ByteArray): Boolean

    /** The peer performed an orderly shutdown. */
    protected abstract suspend fun onClosed(source: FramedSocket)

    /** Read error or framing violation. */
    protected abstract suspend fun onReadError(reason: String, source: FramedSocket)

    /**
     * True if a read [cause] should be ignored (reader continues). Default
     * false; Wi-Fi Direct overrides for its idle socket timeout.
     */
    protected open fun isIgnorableReadError(cause: java.io.IOException): Boolean = false

    /**
     * Start (or restart) the reader for [source]. Must be called with the
     * transport's connection mutex held, matching the previous
     * startReaderLocked() contract.
     */
    protected fun startReader(source: FramedSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            while (isReaderActive(source)) {
                // HS-04: malformed frames and I/O failures use the common
                // cleanup path. Always carry the source socket identity so a
                // cancelled old reader cannot tear down a newer connection.
                val r = try {
                    source.readFrame()
                } catch (e: FrameException) {
                    AppLog.log(tag, "framing error: ${e.message}")
                    onReadError("framing error: ${e.message}", source)
                    return@launch
                } catch (e: CancellationException) {
                    throw e // normal shutdown; not an error
                } catch (e: Exception) {
                    AppLog.log(tag, "reader failed: ${e.javaClass.simpleName}")
                    onReadError("read error: ${e.message}", source)
                    return@launch
                }
                when (r) {
                    is FramedSocket.ReadResult.Frame -> {
                        if (!onFrame(source, r.frame)) {
                            return@launch // socket was replaced or closed
                        }
                    }
                    is FramedSocket.ReadResult.Closed -> {
                        onClosed(source)
                        return@launch
                    }
                    is FramedSocket.ReadResult.Error -> {
                        if (isIgnorableReadError(r.cause)) continue
                        onReadError(r.cause.message ?: "read error", source)
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
