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
 * Privacy: MAC addresses are redacted before storage (M1). Never contains
 * audio, keys, or other personal data — only step/result labels.
 */
object AppLog {
    private const val MAX_ENTRIES = 300

    /** Matches Bluetooth/Wi-Fi MAC addresses like `aa:bb:cc:11:22:33`. */
    private val MAC_RE = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    /** Redacts local Wi-Fi Direct addresses that can appear in socket errors. */
    private val IPV4_RE = Regex("""\\b(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}\\b""")

    data class Entry(val time: String, val tag: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String) {
        // Redact persistent device identifiers before they hit the buffer.
        val withoutMacs = MAC_RE.replace(message, "[MAC]")
        val safe = IPV4_RE.replace(withoutMacs, "[IP]")
        val e = Entry(timeFmt.format(Date()), tag, safe)
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
