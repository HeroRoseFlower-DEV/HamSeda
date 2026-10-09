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
        val realSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = hmac(realSalt, ikm)
        val okm = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val input = previous + info + counter.toByte()
            previous = hmac(prk, input)
            val take = minOf(previous.size, length - pos)
            previous.copyInto(okm, pos, 0, take)
            pos += take
            counter++
        }
        return okm
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
