package com.hamseda.walkie.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA-256 per RFC 5869, implemented over the platform HMAC-SHA-256.
 * (HKDF is a key-derivation function, not a cipher — implementing it from
 * the standard HMAC primitive is the documented, reviewable approach.)
 */
object Hkdf {
    private const val HASH_LEN = 32

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0 && length <= 255 * HASH_LEN) {
            "invalid HKDF output length: $length"
        }
        val generatedSalt = salt.isEmpty()
        val realSalt = if (generatedSalt) ByteArray(HASH_LEN) else salt
        var prk: ByteArray? = null
        var okm: ByteArray? = null
        var previous = ByteArray(0)
        try {
            prk = hmac(realSalt, ikm)
            okm = ByteArray(length)
            var pos = 0
            var counter = 1
            while (pos < length) {
                // Build a single temporary block instead of chained '+' operations,
                // which would leave an extra intermediate copy in the heap.
                val input = ByteArray(previous.size + info.size + 1)
                previous.copyInto(input, 0)
                info.copyInto(input, previous.size)
                input[input.lastIndex] = counter.toByte()
                val next = try {
                    hmac(requireNotNull(prk), input)
                } finally {
                    input.fill(0)
                }
                previous.fill(0)
                previous = next
                val target = requireNotNull(okm)
                val take = minOf(previous.size, length - pos)
                previous.copyInto(target, pos, 0, take)
                pos += take
                counter++
            }
            return requireNotNull(okm)
        } catch (e: Exception) {
            okm?.fill(0)
            throw e
        } finally {
            prk?.fill(0)
            previous.fill(0)
            if (generatedSalt) realSalt.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }
}
