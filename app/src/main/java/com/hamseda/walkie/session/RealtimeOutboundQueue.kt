package com.hamseda.walkie.session

import java.util.ArrayDeque

/**
 * Bounded FIFO for the single outbound sender. Audio is freshness-first:
 * when its budget is reached, the oldest queued audio is evicted so speech
 * doesn't play hundreds of milliseconds late after a slow socket write.
 * Control messages are retained where possible and can displace audio at the
 * total queue cap; a control-only full queue rejects rather than silently
 * deleting an earlier control.
 */
internal class RealtimeOutboundQueue<T>(
    private val maxPending: Int,
    private val maxPendingAudio: Int,
    private val isAudio: (T) -> Boolean,
) {
    private val queue = ArrayDeque<T>()
    private var queuedAudio = 0
    private var droppedAudio = 0L

    init {
        require(maxPending > 0)
        require(maxPendingAudio in 1..maxPending)
    }

    val droppedAudioFrames: Long
        @Synchronized get() = droppedAudio

    @Synchronized
    fun offer(item: T): Boolean {
        val audio = isAudio(item)
        if (audio && queuedAudio >= maxPendingAudio) {
            if (!removeOldestAudio()) {
                droppedAudio++
                return false
            }
        }
        while (queue.size >= maxPending) {
            if (!removeOldestAudio()) {
                if (audio) droppedAudio++
                return false
            }
        }
        queue.addLast(item)
        if (audio) queuedAudio++
        return true
    }

    @Synchronized
    fun poll(): T? {
        val item = queue.pollFirst() ?: return null
        if (isAudio(item)) queuedAudio--
        return item
    }

    @Synchronized
    fun clear() {
        queue.clear()
        queuedAudio = 0
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun pendingAudioFrames(): Int = queuedAudio

    private fun removeOldestAudio(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (isAudio(iterator.next())) {
                iterator.remove()
                queuedAudio--
                droppedAudio++
                return true
            }
        }
        return false
    }
}
