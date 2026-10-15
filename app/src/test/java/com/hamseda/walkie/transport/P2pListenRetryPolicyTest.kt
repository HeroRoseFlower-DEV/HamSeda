package com.hamseda.walkie.transport

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pListenRetryPolicyTest {
    @Test
    fun retriesBusyOnlyWithBoundedBackoff() {
        assertEquals(250L, P2pListenRetryPolicy.delayBeforeRetryMs(WifiP2pManager.BUSY, 1))
        assertEquals(500L, P2pListenRetryPolicy.delayBeforeRetryMs(WifiP2pManager.BUSY, 2))
        assertNull(P2pListenRetryPolicy.delayBeforeRetryMs(WifiP2pManager.BUSY, 3))
    }

    @Test
    fun doesNotRetryNonBusyFailures() {
        assertNull(P2pListenRetryPolicy.delayBeforeRetryMs(WifiP2pManager.ERROR, 1))
        assertNull(P2pListenRetryPolicy.delayBeforeRetryMs(WifiP2pManager.P2P_UNSUPPORTED, 1))
    }
}
