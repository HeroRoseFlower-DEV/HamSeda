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
 * Never contains audio, keys, or personal data — only step/result labels.
 */
object AppLog {
    private const val MAX_ENTRIES = 300

    data class Entry(val time: String, val tag: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String) {
        val e = Entry(timeFmt.format(Date()), tag, message)
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
