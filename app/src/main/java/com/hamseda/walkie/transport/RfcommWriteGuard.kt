package com.hamseda.walkie.transport

import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolves the race between an RFCOMM write finishing and its stall watchdog.
 * Exactly one terminal state wins; once timeout/abort wins, a late successful
 * return from the blocking stream write must not be reported as delivered.
 */
internal class RfcommWriteGuard {
    private val state = AtomicInteger(OPEN)

    fun tryComplete(): Boolean = state.compareAndSet(OPEN, COMPLETED)

    fun tryTimeout(): Boolean = state.compareAndSet(OPEN, TIMED_OUT)

    fun tryAbort(): Boolean = state.compareAndSet(OPEN, ABORTED)

    val didTimeOut: Boolean
        get() = state.get() == TIMED_OUT

    companion object {
        private const val OPEN = 0
        private const val COMPLETED = 1
        private const val TIMED_OUT = 2
        private const val ABORTED = 3
    }
}
