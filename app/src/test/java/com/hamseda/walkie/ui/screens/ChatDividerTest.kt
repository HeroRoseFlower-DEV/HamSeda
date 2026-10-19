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

    private fun atToday(hour: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun atYesterday(hour: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `divider comes after its day's messages in reversed list order`() {
        // Input is oldest-first.
        val messages = listOf(
            msg("y1", atYesterday(10), fromMe = false),
            msg("t1", atToday(9)),
            msg("t2", atToday(10)),
        )
        val items = withDateDividers(messages, "امروز", "دیروز", Locale("fa"))
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
        val messages = listOf(msg("a", atToday(9)), msg("b", atToday(10)))
        val items = withDateDividers(messages, "امروز", "دیروز", Locale("fa"))
        assertEquals(3, items.size)
        assertTrue(items[0] is ChatItem.Msg)
        assertTrue(items[1] is ChatItem.Msg)
        val div = items[2] as ChatItem.Divider
        assertEquals("امروز", div.label)
    }

    @Test
    fun `grouping flags still computed across the divider`() {
        // Two same-sender messages, one per day: each is its own run.
        val messages = listOf(
            msg("y1", atYesterday(10)),
            msg("t1", atToday(9)),
        )
        val items = withDateDividers(messages, "امروز", "دیروز", Locale("fa"))
        assertEquals(4, items.size)
        val mToday = items[0] as ChatItem.Msg
        val mYesterday = items[2] as ChatItem.Msg
        assertTrue(mToday.isFirstInGroup && mToday.isLastInGroup)
        assertTrue(mYesterday.isFirstInGroup && mYesterday.isLastInGroup)
    }
}
