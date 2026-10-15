package com.hamseda.walkie.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log: a bounded ring buffer of timestamped events from
 * the transports and session layer, viewable (and copyable) from the
 * Diagnostics screen. This is the primary remote-debugging tool — when a
 * connection fails on a user's phone, the log shows exactly which step
 * failed instead of a generic error banner.
 *
 * Privacy: Bluetooth MAC and IPv4 addresses are redacted before storage.
 * Peer names, audio, keys, and pairing material are not logged.
 */
object AppLog {
    private const val MAX_ENTRIES = 300
    private const val MAX_MESSAGE_CHARS = 1024
    private const val MAX_INPUT_CHARS = 4096
    private const val MAX_TAG_CHARS = 48

    /** Matches MAC addresses with either colon or hyphen separators. */
    private val MAC_RE = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])")
    /** Redacts local Wi-Fi Direct addresses that can appear in socket errors. */
    private val IPV4_RE = Regex("""\b(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}\b""")
    /**
     * Candidate IPv6 tokens must contain at least two colons; validating the
     * candidate below prevents ordinary punctuation (for example the colon
     * in "IOException: read failed") and time-like values from being redacted.
     */
    private val IPV6_CANDIDATE_RE =
        Regex("""(?i)(?<![0-9a-z:.])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[a-z0-9_.-]+)?(?![0-9a-z:.])""")
    /** Redacts explicitly labeled secret material in exception/framework text. */
    private val SECRET_FIELD_RE = Regex(
        """(?i)\\b((?:private[_ -]?key|derived[_ -]?key|session[_ -]?key|audio[_ -]?key|control[_ -]?key|key[_ -]?material|nonce|sas(?:[_ -]?code|[_ -]?seed)?)\\s*[:=]\\s*)([^\\s,;]+)"""
    )
    /** Prevents attacker-controlled newlines/control bytes from forging log entries. */
    private val CONTROL_RE = Regex("[\\r\\n\\u0000-\\u001F\\u007F]+")

    data class Entry(val time: String, val tag: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String) {
        // Bound untrusted text before regex/address parsing to cap CPU and memory work.
        val inputWasTruncated = message.length > MAX_INPUT_CHARS
        val boundedInput = message.take(MAX_INPUT_CHARS)
        // Normalize first so an exception/device name cannot inject fake lines.
        val oneLine = CONTROL_RE.replace(boundedInput, " ")
        // Redact explicitly labeled secret fields before storing or sharing logs.
        val withoutSecrets = SECRET_FIELD_RE.replace(oneLine) { match ->
            match.groupValues[1] + "[REDACTED]"
        }
        // Redact identifiers before they hit the in-memory buffer or dump/share.
        val withoutMacs = MAC_RE.replace(withoutSecrets, "[MAC]")
        val withoutIpv4 = IPV4_RE.replace(withoutMacs, "[IP]")
        val redacted = IPV6_CANDIDATE_RE.replace(withoutIpv4) { match ->
            val literal = match.value.substringBefore('%')
            val isIpv6 = try {
                java.net.InetAddress.getByName(literal) is java.net.Inet6Address
            } catch (_: Exception) {
                false
            }
            if (isIpv6) "[IPv6]" else match.value
        }
        val safeMessage = if (inputWasTruncated || redacted.length > MAX_MESSAGE_CHARS) {
            redacted.take(MAX_MESSAGE_CHARS - "[truncated]".length) + "[truncated]"
        } else redacted
        val safeTag = CONTROL_RE.replace(tag, " ").take(MAX_TAG_CHARS)
        val e = Entry(timeFmt.format(Date()), safeTag, safeMessage)
        buffer.addLast(e)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    /** Plain-text dump for copy/share. */
    @Synchronized
    fun dump(): String = buildString {
        for (e in buffer) appendLine("${e.time} [${e.tag}] ${e.message}")
    }
}
