package com.hamseda.walkie.session

/**
 * Monotonic uint32 wire sequence allocator for one session.
 *
 * A session is ended before the uint32 space is exhausted. Although random
 * AEAD nonces protect nonce uniqueness, they do not resolve replay-window
 * ambiguity or guarantee protocol-level ordering across sequence rollover.
 */
internal class SessionSequenceCounter(
    private val safetyLimit: Long = DEFAULT_SAFETY_LIMIT,
) {
    private var nextValue: Long = 0L

    init {
        require(safetyLimit in 1L..UINT32_MAX)
    }

    @Synchronized
    fun next(): Long {
        if (nextValue >= safetyLimit) throw SequenceExhaustedException()
        return nextValue++
    }

    @Synchronized
    fun reset() {
        nextValue = 0L
    }

    class SequenceExhaustedException :
        IllegalStateException("Session wire sequence safety limit reached")

    companion object {
        const val UINT32_MAX = 0xFFFFFFFFL
        // Leaves 65,536 sequence values unused, so a session ends well before
        // uint32 rollover. At 50 frames/s this guard is still ~22 minutes away
        // from the theoretical wrap, even if every frame were audio.
        const val DEFAULT_SAFETY_LIMIT = 0xFFFF0000L
    }
}
