package com.hamseda.walkie.audio

import java.util.TreeMap

/**
 * Adaptive jitter buffer for 20 ms voice frames.
 *
 * - Buffers [targetFrames] (default ~60 ms) before first playout to improve
 *   resilience to short wireless scheduling jitter.
 * - Drops stale frames and caps the queue; old speech is discarded rather
 *   than replayed long after it was spoken.
 * - When a packet is missing, waits only [maxWaitMs] before concealment so
 *   one gap cannot stall playout for a long interval.
 *
 * [nowMs] is injectable for deterministic unit tests.
 */
class JitterBuffer(
    private val targetFrames: Int = 3,
    private val maxFrames: Int = 8,
    private val maxLateMs: Long = 180,
    private val maxWaitMs: Long = 40,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class Packet(val seq: Long, val receivedAt: Long, val payload: ByteArray)

    sealed interface TakeResult {
        data class Frame(val packet: Packet) : TakeResult
        /** Expected seq missing; waited [maxWaitMs] — conceal and advance. */
        data class Lost(val skippedSeq: Long) : TakeResult
        /** Nothing to play yet (initial buffering or empty). */
        data object NotReady : TakeResult
    }

    private val queue = TreeMap<Long, Packet>()
    private var nextSeq: Long = -1 // -1 = not started
    private var waitStartedAt: Long = 0
    private var primed = false

    var received: Long = 0; private set
    var played: Long = 0; private set
    var droppedStale: Long = 0; private set
    var lost: Long = 0; private set

    @Synchronized
    fun push(seq: Long, payload: ByteArray) {
        received++
        val now = nowMs()
        // HS-10: validate the frame BEFORE making room. A stale or duplicate
        // frame must not evict a useful queued frame. Eviction policy: when
        // full, drop the OLDEST queued frame (lowest seq) to make room for a
        // validated newer frame — this bounds memory and latency.
        if (nextSeq != -1L && seq < nextSeq) {
            droppedStale++ // too late to play; drop without evicting
            return
        }
        if (queue.containsKey(seq)) {
            droppedStale++ // duplicate; drop without evicting
            return
        }
        if (queue.size >= maxFrames) {
            // Runaway protection: drop the oldest queued frame. If it was the
            // expected frame, advance the playhead now; otherwise one evicted
            // packet can make playback wait one maxWaitMs interval per stale seq.
            val evicted = queue.pollFirstEntry()
            if (evicted != null) {
                if (nextSeq != -1L && evicted.key >= nextSeq) {
                    nextSeq = evicted.key + 1
                    waitStartedAt = now
                }
                evicted.value.payload.fill(0)
                droppedStale++
            }
        }
        // The caller can clear its decrypted input after this queue-owned copy is made.
        queue[seq] = Packet(seq, now, payload.copyOf())
    }

    @Synchronized
    fun takeNext(): TakeResult {
        val now = nowMs()
        // Expire stale head frames.
        while (true) {
            val head = queue.firstEntry() ?: break
            if (now - head.value.receivedAt > maxLateMs) {
                queue.pollFirstEntry()
                head.value.payload.fill(0)
                droppedStale++
                if (nextSeq != -1L && head.key >= nextSeq) nextSeq = head.key + 1
            } else break
        }
        if (!primed) {
            if (queue.size < targetFrames) return TakeResult.NotReady
            primed = true
            nextSeq = queue.firstKey()
            waitStartedAt = now
        }
        val head = queue.firstEntry() ?: run {
            // Queue drained mid-stream: wait briefly, then re-prime.
            if (now - waitStartedAt > maxWaitMs * 3) {
                primed = false
                nextSeq = -1
            }
            return TakeResult.NotReady
        }
        if (head.key == nextSeq) {
            queue.pollFirstEntry()
            nextSeq++
            played++
            waitStartedAt = now
            return TakeResult.Frame(head.value)
        }
        if (head.key > nextSeq) {
            // Gap: expected frame missing.
            if (now - waitStartedAt >= maxWaitMs) {
                val skipped = nextSeq
                nextSeq++
                lost++
                waitStartedAt = now
                return TakeResult.Lost(skipped)
            }
            return TakeResult.NotReady
        }
        // head.key < nextSeq: stale duplicate, drop.
        queue.pollFirstEntry()
        head.value.payload.fill(0)
        droppedStale++
        return takeNext()
    }

    @Synchronized
    fun reset() {
        queue.values.forEach { it.payload.fill(0) }
        queue.clear()
        nextSeq = -1
        primed = false
        received = 0; played = 0; droppedStale = 0; lost = 0
    }

    @Synchronized
    fun bufferedFrames(): Int = queue.size
}
