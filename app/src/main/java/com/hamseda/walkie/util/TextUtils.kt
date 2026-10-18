package com.hamseda.walkie.util

/**
 * Truncates this string so its UTF-8 encoding is at most [maxBytes] bytes,
 * never splitting a character (or surrogate pair). Code points are counted
 * by their real UTF-8 length: Persian/Arabic letters cost 2 bytes, most
 * emoji cost 4.
 *
 * Needed because wire limits are byte limits: a "64-character" Persian name
 * can be 128 UTF-8 bytes. Truncating by [String.take] would let oversize
 * names through and break profile encoding.
 */
fun String.takeUtf8Bytes(maxBytes: Int): String {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    var bytes = 0
    var end = 0
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val charBytes = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + charBytes > maxBytes) break
        bytes += charBytes
        i += Character.charCount(cp)
        end = i
    }
    return substring(0, end)
}
