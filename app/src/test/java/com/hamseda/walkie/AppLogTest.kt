package com.hamseda.walkie

import com.hamseda.walkie.util.AppLog
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AppLogTest {

    @After
    fun clearLog() {
        AppLog.clear()
    }

    @Test
    fun `diagnostics redact Bluetooth MAC and local IPv4 addresses`() {
        AppLog.clear()
        AppLog.log("test", "peer=192.168.49.1 bluetooth=aa:bb:cc:11:22:33")

        val dump = AppLog.dump()
        assertFalse(dump.contains("192.168.49.1"))
        assertFalse(dump.contains("aa:bb:cc:11:22:33"))
        assertTrue(dump.contains("[IP]"))
        assertTrue(dump.contains("[MAC]"))
    }
}
