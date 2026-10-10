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

    @Test
    fun `diagnostics redact IPv6 literals and zone identifiers`() {
        AppLog.clear()
        AppLog.log("test", "compressed=2001:db8::1 local=fe80::1%wlan0 loopback=::1")

        val dump = AppLog.dump()
        assertFalse(dump.contains("2001:db8::1"))
        assertFalse(dump.contains("fe80::1%wlan0"))
        assertFalse(dump.contains("::1"))
        assertTrue(dump.contains("[IPv6]"))
    }

    @Test
    fun `diagnostics redact hyphenated MAC addresses`() {
        AppLog.clear()
        AppLog.log("test", "device=AA-BB-CC-11-22-33")
        assertFalse(AppLog.dump().contains("AA-BB-CC-11-22-33"))
        assertTrue(AppLog.dump().contains("[MAC]"))
    }

    @Test
    fun `diagnostics normalize control characters and cap message length`() {
        AppLog.clear()
        AppLog.log("test", "before\r\nafter\u0000" + "x".repeat(5000))

        val message = AppLog.entries.value.single().message
        assertFalse(message.contains('\n'))
        assertFalse(message.contains('\r'))
        assertFalse(message.any { it.code < 0x20 || it.code == 0x7F })
        assertTrue(message.length <= 1024)
        assertTrue(message.endsWith("[truncated]"))
    }
}
