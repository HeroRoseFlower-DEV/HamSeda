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
    private val onDiscard: (T) -> Unit = {},
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
                onDiscard(item)
                return false
            }
        }
        while (queue.size >= maxPending) {
            if (!removeOldestAudio()) {
                if (audio) droppedAudio++
                onDiscard(item)
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

    /**
     * Removes at most [maxItems] AUDIO items from the queue head. Stops at a
     * control item instead of reordering it behind later audio. Used to batch
     * already-queued speech on slow byte-stream transports.
     */
    @Synchronized
    fun pollConsecutiveAudio(maxItems: Int): List<T> {
        require(maxItems >= 0)
        if (maxItems == 0) return emptyList()
        val result = ArrayList<T>(maxItems)
        while (result.size < maxItems) {
            val head = queue.peekFirst() ?: break
            if (!isAudio(head)) break
            queue.removeFirst()
            queuedAudio--
            result.add(head)
        }
        return result
    }

    /**
     * Counts an audio frame that was dequeued but expired before serialization.
     * It is no longer in this queue, but belongs in the same freshness-drop metric.
     */
    @Synchronized
    fun recordDroppedAudioFrame() {
        droppedAudio++
    }

    @Synchronized
    fun clear() {
        queue.forEach(onDiscard)
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
            val candidate = iterator.next()
            if (isAudio(candidate)) {
                iterator.remove()
                queuedAudio--
                droppedAudio++
                onDiscard(candidate)
                return true
            }
        }
        return false
    }
}
