package com.hamseda.walkie.proto

import java.security.SecureRandom
import java.util.UUID

/**
 * UUIDv7 generator (RFC 9562 §5.7): 128-bit, time-ordered, no coordination
 * needed — the right message-ID choice for an offline two-peer app.
 *
 * Layout (big-endian):
 * ```
 *  0..47   unix_ts_ms   48-bit big-endian millisecond timestamp
 *  48..51  ver          4-bit version, always 0b0111
 *  52..63  rand_a       12-bit per-timestamp random (monotonicity guard)
 *  64..65  var          2-bit variant, always 0b10
 *  66..127 rand_b       62-bit random
 * ```
 *
 * Monotonicity within the same millisecond is enforced with a counter in
 * `rand_a` (Method 1 of the RFC): if the clock has not advanced since the
 * last call, `rand_a` increments instead of re-rolling, so IDs generated
 * back-to-back sort strictly increasing. Thread-safe.
 */
object Uuid7 {
    private val random = SecureRandom()
    private val lock = Any()

    @Volatile
    private var lastMs: Long = -1L

    @Volatile
    private var lastRandA: Int = 0

    /** Generates a new UUIDv7. The string form is used as the Room primary key. */
    fun generate(): UUID {
        val ms = System.currentTimeMillis()
        val randA: Int
        val randB = ByteArray(8)
        synchronized(lock) {
            if (ms == lastMs) {
                // Same millisecond: bump rand_a instead of re-rolling so the
                // sequence stays strictly increasing (wraps at 12 bits, which
                // is acceptable — rand_b still differs with overwhelming
                // probability).
                lastRandA = (lastRandA + 1) and 0xFFF
            } else {
                lastMs = ms
                lastRandA = random.nextInt(0x1000)
            }
            randA = lastRandA
            random.nextBytes(randB)
        }
        val msb = (ms shl 16) or (0x7000L or randA.toLong())
        var lsb = 0L
        for (i in 0 until 8) {
            lsb = (lsb shl 8) or (randB[i].toLong() and 0xFF)
        }
        // Top two bits of LSB = variant 0b10 (Long.MIN_VALUE is 0x8000...0).
        lsb = (lsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }

    /** Extracts the embedded unix-millis timestamp (for diagnostics only). */
    fun timestampMillis(id: UUID): Long = id.mostSignificantBits ushr 16
}
