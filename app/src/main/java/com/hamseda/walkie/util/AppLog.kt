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
    private const val MAX_TAG_CHARS = 48

    /** Matches MAC addresses with either colon or hyphen separators. */
    private val MAC_RE = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])")
    /** Redacts local Wi-Fi Direct addresses that can appear in socket errors. */
    private val IPV4_RE = Regex("""\b(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}\b""")
    /** Includes compressed/expanded IPv6 literals and optional interface zones. */
    private val IPV6_RE = Regex("""(?i)(?<![0-9a-f:])(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:){0,7}:(?:[0-9a-f]{1,4}:?){0,7}|::(?:[0-9a-f]{1,4}:?){0,7})(?:%[a-z0-9_.-]+)?(?![0-9a-f:])""")
    /** Prevents attacker-controlled newlines/control bytes from forging log entries. */
    private val CONTROL_RE = Regex("[\\r\\n\\u0000-\\u001F\\u007F]+")

    data class Entry(val time: String, val tag: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String) {
        // Normalize first so an exception/device name cannot inject fake lines.
        val oneLine = CONTROL_RE.replace(message, " ")
        // Redact identifiers before they hit the in-memory buffer or dump/share.
        val withoutMacs = MAC_RE.replace(oneLine, "[MAC]")
        val withoutIpv4 = IPV4_RE.replace(withoutMacs, "[IP]")
        val redacted = IPV6_RE.replace(withoutIpv4, "[IPv6]")
        val safeMessage = if (redacted.length > MAX_MESSAGE_CHARS) {
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
