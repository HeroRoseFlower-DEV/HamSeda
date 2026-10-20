package com.hamseda.walkie.ui.screens

import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.messaging.MessagingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * Regression test for the date-pill ordering bug: in the newest-first list
 * backing the reversed LazyColumn, each day's divider must come AFTER its
 * messages, so visually it renders ABOVE (before) them. The old code
 * prepended the divider, rendering «امروز» below the day's messages.
 *
 * Round-7 L22: all timestamps in one arrangement derive from a SINGLE
 * captured instant, and the arrangement re-runs if the wall clock
 * crosses midnight while it executes — the code under test re-derives
 * "today" at call time, so a run straddling midnight would otherwise
 * flip the expected labels and false-fail.
 */
class ChatDividerTest {

    private fun msg(id: String, ts: Long, fromMe: Boolean = true) =
        MessagingEngine.MessageView(
            id = id,
            isFromMe = fromMe,
            body = "x",
            timestamp = ts,
            state = MessageState.SENT,
        )

    /** Milliseconds at [hour]:00 on the day of [base] plus [dayOffset] days. */
    private fun atHour(base: Calendar, dayOffset: Int, hour: Int): Long =
        (base.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayKeyOf(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    /**
     * Runs [block] with one captured "now"; if the day rolled over while
     * it ran, the expectations and the code under test used different
     * days — re-run on the new day instead of flaking.
     */
    private fun <T> onStableDay(block: (Calendar) -> T): T {
        repeat(3) {
            val now = Calendar.getInstance()
            val result = block(now)
            if (dayKeyOf(System.currentTimeMillis()) == dayKeyOf(now.timeInMillis)) {
                return result
            }
        }
        return block(Calendar.getInstance())
    }

    @Test
    fun `divider comes after its day's messages in reversed list order`() {
        // Input is oldest-first.
        val items = onStableDay { now ->
            val messages = listOf(
                msg("y1", atHour(now, -1, 10), fromMe = false),
                msg("t1", atHour(now, 0, 9)),
                msg("t2", atHour(now, 0, 10)),
            )
            withDateDividers(messages, "امروز", "دیروز", Locale("fa"))
        }
        assertEquals(5, items.size)
        val m0 = items[0] as ChatItem.Msg
        val m1 = items[1] as ChatItem.Msg
        assertEquals("t2", m0.msg.id)
        assertEquals("t1", m1.msg.id)
        val dToday = items[2] as ChatItem.Divider
        assertEquals("امروز", dToday.label)
        val m3 = items[3] as ChatItem.Msg
        assertEquals("y1", m3.msg.id)
        val dYesterday = items[4] as ChatItem.Divider
        assertEquals("دیروز", dYesterday.label)
    }

    @Test
    fun `single day yields one divider after all messages`() {
        val items = onStableDay { now ->
            val messages = listOf(msg("a", atHour(now, 0, 9)), msg("b", atHour(now, 0, 10)))
            withDateDividers(messages, "امروز", "دیروز", Locale("fa"))
        }
        assertEquals(3, items.size)
        assertTrue(items[0] is ChatItem.Msg)
        assertTrue(items[1] is ChatItem.Msg)
        val div = items[2] as ChatItem.Divider
        assertEquals("امروز", div.label)
    }

    @Test
    fun `grouping flags still computed across the divider`() {
        // Two same-sender messages, one per day: each is its own run.
        val items = onStableDay { now ->
            val messages = listOf(
                msg("y1", atHour(now, -1, 10)),
                msg("t1", atHour(now, 0, 9)),
            )
            withDateDividers(messages, "امروز", "دیروز", Locale("fa"))
        }
        assertEquals(4, items.size)
        val mToday = items[0] as ChatItem.Msg
        val mYesterday = items[2] as ChatItem.Msg
        assertTrue(mToday.isFirstInGroup && mToday.isLastInGroup)
        assertTrue(mYesterday.isFirstInGroup && mYesterday.isLastInGroup)
    }
}
