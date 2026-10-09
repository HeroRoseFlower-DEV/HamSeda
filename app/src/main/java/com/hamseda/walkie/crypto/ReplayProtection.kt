package com.hamseda.walkie.crypto

/**
 * Sliding-window replay / duplicate detector over uint32 sequence numbers.
 *
 * Accepts a sequence number iff it has not been seen before and is not older
 * than [windowSize] behind the highest accepted number. Out-of-order frames
 * *inside* the window are accepted (wireless links reorder); anything at or
 * below `highest - windowSize`, or any repeat, is rejected.
 *
 * Not thread-safe — the session layer serializes frame processing.
 */
class ReplayProtection(private val windowSize: Int = 128) {
    private var highest: Long = -1L
    private val seen = BooleanArray(windowSize)

    /** @return true if the frame may be processed, false if it must be dropped. */
    fun accept(seq: Long): Boolean {
        require(seq in 0..0xFFFFFFFFL) { "seq out of uint32 range" }
        if (highest == -1L) {
            highest = seq
            seen[(seq % windowSize).toInt()] = true
            return true
        }
        if (seq > highest) {
            val jump = seq - highest
            if (jump >= windowSize) {
                // Far jump: stale window, start over.
                seen.fill(false)
            } else {
                // Clear bits that fall out of the window.
                var s = highest + 1
                while (s <= seq) {
                    seen[(s % windowSize).toInt()] = false
                    s++
                }
            }
            highest = seq
            seen[(seq % windowSize).toInt()] = true
            return true
        }
        if (seq <= highest - windowSize) return false // too old
        val idx = (seq % windowSize).toInt()
        if (seen[idx]) return false // duplicate / replay
        seen[idx] = true
        return true
    }

    fun reset() {
        highest = -1L
        seen.fill(false)
    }
}
