package com.hamseda.walkie

import com.hamseda.walkie.proto.DenyReason
import com.hamseda.walkie.session.FloorController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorControlTest {

    private class Clock(var now: Long = 0) {
        fun get(): Long = now
    }

    private fun controller(clock: Clock): Pair<FloorController, MutableList<FloorController.Event>> {
        val events = mutableListOf<FloorController.Event>()
        val c = FloorController(clock::get, object : FloorController.Listener {
            override fun onFloorEvent(event: FloorController.Event) {
                events.add(event)
            }
        })
        return c to events
    }

    @Test
    fun `self request then grant then release`() {
        val clock = Clock()
        val (c, events) = controller(clock)
        assertTrue(c.requestFloor())
        assertTrue(c.selfRequestPending)
        c.onGrant(30_000)
        assertEquals(FloorController.Holder.SELF, c.holder)
        assertTrue(events[0] is FloorController.Event.GrantedToSelf)
        assertTrue(c.releaseSelf())
        assertEquals(FloorController.Holder.NONE, c.holder)
        assertTrue(events[1] is FloorController.Event.SelfReleased)
    }

    @Test
    fun `deny while peer holds the floor`() {
        val clock = Clock()
        val (c, events) = controller(clock)
        assertTrue(c.onPeerRequest())
        assertEquals(FloorController.Holder.PEER, c.holder)
        assertFalse(c.requestFloor()) // busy -> Denied event
        val denied = events.last() as FloorController.Event.Denied
        assertEquals(DenyReason.PEER_BUSY, denied.reason)
    }

    @Test
    fun `peer request denied while self pending`() {
        val clock = Clock()
        val (c, _) = controller(clock)
        assertTrue(c.requestFloor())
        assertFalse(c.onPeerRequest())
        assertEquals(FloorController.Holder.NONE, c.holder)
    }

    @Test
    fun `peer audio extends lease - silent peer times out`() {
        val clock = Clock()
        val (c, events) = controller(clock)
        assertTrue(c.onPeerRequest())
        clock.now += FloorController.PEER_LEASE_MS - 1_000
        c.onPeerAudio() // extends
        clock.now += FloorController.PEER_LEASE_MS - 1_000
        c.checkTimeouts()
        assertEquals(FloorController.Holder.PEER, c.holder) // still alive
        clock.now += FloorController.PEER_LEASE_MS + 1
        c.checkTimeouts()
        assertEquals(FloorController.Holder.NONE, c.holder)
        assertTrue(events.last() is FloorController.Event.PeerReleasedFloor)
    }

    @Test
    fun `self transmission is capped`() {
        val clock = Clock()
        val (c, _) = controller(clock)
        assertTrue(c.requestFloor())
        c.onGrant(120_000) // peer grants generously...
        clock.now += FloorController.MAX_SELF_TX_MS + 1
        c.checkTimeouts() // ...but our own cap still applies
        assertEquals(FloorController.Holder.NONE, c.holder)
    }

    @Test
    fun `double grant is ignored`() {
        val clock = Clock()
        val (c, events) = controller(clock)
        assertTrue(c.requestFloor())
        c.onGrant(30_000)
        c.onGrant(30_000)
        assertEquals(1, events.filterIsInstance<FloorController.Event.GrantedToSelf>().size)
    }

    @Test
    fun `cancel request clears pending`() {
        val clock = Clock()
        val (c, _) = controller(clock)
        assertTrue(c.requestFloor())
        c.cancelRequest()
        assertFalse(c.selfRequestPending)
        c.onGrant(30_000) // late grant ignored
        assertEquals(FloorController.Holder.NONE, c.holder)
    }

    @Test
    fun `peer release frees the floor`() {
        val clock = Clock()
        val (c, events) = controller(clock)
        assertTrue(c.onPeerRequest())
        c.onPeerRelease()
        assertEquals(FloorController.Holder.NONE, c.holder)
        assertTrue(events.last() is FloorController.Event.PeerReleasedFloor)
    }
}
