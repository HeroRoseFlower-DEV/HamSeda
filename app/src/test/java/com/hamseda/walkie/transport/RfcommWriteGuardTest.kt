package com.hamseda.walkie.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class RfcommWriteGuardTest {
    @Test
    fun completedWriteCannotBeTimedOutLater() {
        val guard = RfcommWriteGuard()

        assertTrue(guard.tryComplete())
        assertFalse(guard.tryTimeout())
        assertFalse(guard.didTimeOut)
    }

    @Test
    fun timeoutWinsAndRejectsALateWriteCompletion() {
        val guard = RfcommWriteGuard()

        assertTrue(guard.tryTimeout())
        assertTrue(guard.didTimeOut)
        assertFalse(guard.tryComplete())
        assertFalse(guard.tryAbort())
    }

    @Test
    fun cancellationWinsAndStopsTheTimeoutPath() {
        val guard = RfcommWriteGuard()

        assertTrue(guard.tryAbort())
        assertFalse(guard.tryTimeout())
        assertFalse(guard.tryComplete())
        assertFalse(guard.didTimeOut)
    }

    @Test
    fun concurrentCompletionAndTimeoutHaveExactlyOneWinner() {
        repeat(100) {
            val guard = RfcommWriteGuard()
            val start = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            val timedOut = AtomicBoolean(false)
            val a = Thread {
                start.await()
                completed.set(guard.tryComplete())
            }
            val b = Thread {
                start.await()
                timedOut.set(guard.tryTimeout())
            }

            a.start()
            b.start()
            start.countDown()
            a.join()
            b.join()

            assertTrue(completed.get() xor timedOut.get())
            assertTrue(guard.didTimeOut == timedOut.get())
        }
    }
}
