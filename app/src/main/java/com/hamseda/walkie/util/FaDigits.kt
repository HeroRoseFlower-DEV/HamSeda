package com.hamseda.walkie.util

private val FA_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

/**
 * Map ASCII digits to Persian digits for user-visible numeric strings
 * (SAS codes, fingerprints). Non-digit characters pass through unchanged.
 */
fun String.toFaDigits(): String {
    if (none { it in '0'..'9' }) return this
    val out = StringBuilder(length)
    for (c in this) {
        out.append(if (c in '0'..'9') FA_DIGITS[c - '0'] else c)
    }
    return out.toString()
}
