package com.hamseda.walkie.session

import com.hamseda.walkie.proto.DenyReason

/**
 * Deterministic half-duplex floor control for one-to-one push-to-talk.
 *
 * Exactly one side may hold the floor at a time:
 * - Self presses PTT → [requestFloor]; the peer answers GRANT or DENY.
 * - Peer requests → [onPeerRequest] grants only when the floor is free and
 *   self is not waiting; otherwise denies with [DenyReason.PEER_BUSY].
 * - The grant carries a lease: a crashed peer cannot lock the channel
 *   forever — [checkTimeouts] releases a silent peer, and caps a single
 *   self transmission at [MAX_SELF_TX_MS].
 * - Audio frames from the peer extend its lease (proof of liveness).
 *
 * Pure logic, no Android dependencies — fully unit-tested.
 */
class FloorController(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val listener: Listener? = null,
) {
    enum class Holder { NONE, SELF, PEER }

    sealed interface Event {
        /** Peer granted the floor to us — start transmitting. */
        data class GrantedToSelf(val leaseMs: Long) : Event
        /** Self released (or lease expired) — stop transmitting now. */
        data object SelfReleased : Event
        data class Denied(val reason: Byte) : Event
        /** Peer took the floor — expect incoming audio. */
        data object PeerTookFloor : Event
        /** Peer released or timed out — floor is free. */
        data object PeerReleasedFloor : Event
    }

    interface Listener {
        fun onFloorEvent(event: Event)
    }

    var holder: Holder = Holder.NONE
        private set
    var selfRequestPending: Boolean = false
        private set

    private var leaseExpiresAt: Long = 0L
    private var selfTxStartedAt: Long = 0L

    /** Self pressed PTT. Returns false if a request is already in flight. */
    fun requestFloor(): Boolean {
        if (selfRequestPending) return false
        if (holder == Holder.SELF) return true // already transmitting
        if (holder == Holder.PEER) {
            listener?.onFloorEvent(Event.Denied(DenyReason.PEER_BUSY))
            return false
        }
        selfRequestPending = true
        return true
    }

    /** Self released PTT (or the UI cancelled). */
    fun cancelRequest() {
        selfRequestPending = false
    }

    /** GRANT arrived from the peer. */
    fun onGrant(leaseMs: Long) {
        if (!selfRequestPending || holder != Holder.NONE) return
        selfRequestPending = false
        holder = Holder.SELF
        val now = clock()
        selfTxStartedAt = now
        leaseExpiresAt = now + leaseMs.coerceAtMost(MAX_SELF_TX_MS)
        listener?.onFloorEvent(Event.GrantedToSelf(leaseMs))
    }

    /** DENY arrived from the peer. */
    fun onDeny(reason: Byte) {
        if (!selfRequestPending) return
        selfRequestPending = false
        listener?.onFloorEvent(Event.Denied(reason))
    }

    /**
     * Peer's FLOOR_REQUEST arrived.
     * @return true if granted (caller must send GRANT), false if denied.
     */
    fun onPeerRequest(): Boolean {
        if (holder != Holder.NONE || selfRequestPending) return false
        holder = Holder.PEER
        leaseExpiresAt = clock() + PEER_LEASE_MS
        listener?.onFloorEvent(Event.PeerTookFloor)
        return true
    }

    /** Peer's audio frame arrived — proof the peer is alive; extend lease. */
    fun onPeerAudio() {
        if (holder == Holder.PEER) {
            leaseExpiresAt = clock() + PEER_LEASE_MS
        }
    }

    /** Self released the floor (PTT up / timeout). */
    fun releaseSelf(): Boolean {
        selfRequestPending = false
        if (holder != Holder.SELF) return false
        holder = Holder.NONE
        listener?.onFloorEvent(Event.SelfReleased)
        return true
    }

    /** Peer's FLOOR_RELEASE arrived. */
    fun onPeerRelease() {
        if (holder != Holder.PEER) return
        holder = Holder.NONE
        listener?.onFloorEvent(Event.PeerReleasedFloor)
    }

    /**
     * Enforces leases. Call periodically (e.g. every second) while in
     * session. A silent peer loses the floor; an over-long self
     * transmission is cut.
     */
    fun checkTimeouts() {
        val now = clock()
        when (holder) {
            Holder.SELF -> if (now >= leaseExpiresAt) {
                holder = Holder.NONE
                selfRequestPending = false
                listener?.onFloorEvent(Event.SelfReleased)
            }
            Holder.PEER -> if (now >= leaseExpiresAt) {
                holder = Holder.NONE
                listener?.onFloorEvent(Event.PeerReleasedFloor)
            }
            Holder.NONE -> Unit
        }
    }

    fun reset() {
        holder = Holder.NONE
        selfRequestPending = false
        leaseExpiresAt = 0L
        selfTxStartedAt = 0L
    }

    companion object {
        /** Lease granted to a peer; extended by each audio frame. */
        const val PEER_LEASE_MS = 15_000L
        /** Lease requested from a peer for our own transmission. */
        const val SELF_LEASE_REQUEST_MS = 30_000L
        /** Hard cap for one continuous self transmission. */
        const val MAX_SELF_TX_MS = 60_000L
    }
}
