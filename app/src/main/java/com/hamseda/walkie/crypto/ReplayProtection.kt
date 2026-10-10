package com.hamseda.walkie.crypto

import java.util.HashSet

/**
 * Sliding-window replay / duplicate detector over uint32 sequence numbers.
 *
 * Accepts a sequence number iff it has not been seen before and is not older
 * than [windowSize] behind the highest accepted number under uint32 serial
 * arithmetic. A forward distance in (0, 2^31) means newer; the other half of
 * the uint32 space is interpreted as older. This supports the ordinary
 * 0xFFFFFFFF -> 0 rollover without treating the first post-rollover frames
 * as stale. Values are stored explicitly rather than in a modulo-indexed bit
 * array, so custom window sizes cannot collide at the uint32 wrap boundary.
 *
 * Not thread-safe — the session layer serializes frame processing.
 */
class ReplayProtection(private val windowSize: Int = 128) {
    private val mask = 0xFFFFFFFFL
    private val halfRange = 0x80000000L
    private var highest: Long = -1L
    private val seen = HashSet<Long>()

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    /** @return true if the frame may be processed, false if it must be dropped. */
    fun accept(seq: Long): Boolean {
        require(seq in 0..0xFFFFFFFFL) { "seq out of uint32 range" }
        if (highest == -1L) {
            highest = seq
            seen.add(seq)
            return true
        }

        val forwardDistance = (seq - highest) and mask
        if (forwardDistance == 0L) return false

        if (forwardDistance < halfRange) {
            val jump = forwardDistance
            if (jump >= windowSize) {
                // A large forward jump makes all previously seen numbers stale.
                seen.clear()
            } else {
                // Evict exactly the values that move behind the replay window.
                var step = 1L
                while (step <= jump) {
                    seen.remove((highest - windowSize + step) and mask)
                    step++
                }
            }
            highest = seq
            seen.add(seq)
            return true
        }

        val backwardDistance = (highest - seq) and mask
        if (backwardDistance >= windowSize) return false // too old or ambiguous serial distance
        return seen.add(seq) // accepts an unseen out-of-order frame once
    }

    fun reset() {
        highest = -1L
        seen.clear()
    }
}
