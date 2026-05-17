package com.openautolink.app.ui.projection

import org.junit.Assert.assertEquals
import org.junit.Test

class CarHotspotReconnectPolicyTest {
    @Test
    fun unexpectedDropsUseShortInitialRetries() {
        assertEquals(500L, CarHotspotReconnectPolicy.delayMs(attempt = 1, protocolError = false))
        assertEquals(500L, CarHotspotReconnectPolicy.delayMs(attempt = 2, protocolError = false))
    }

    @Test
    fun repeatedUnexpectedDropsBackOffAndCap() {
        assertEquals(12_000L, CarHotspotReconnectPolicy.delayMs(attempt = 3, protocolError = false))
        assertEquals(30_000L, CarHotspotReconnectPolicy.delayMs(attempt = 9, protocolError = false))
    }

    @Test
    fun protocolErrorsUseLongerBackoff() {
        assertEquals(5_000L, CarHotspotReconnectPolicy.delayMs(attempt = 1, protocolError = true))
        assertEquals(10_000L, CarHotspotReconnectPolicy.delayMs(attempt = 2, protocolError = true))
        assertEquals(30_000L, CarHotspotReconnectPolicy.delayMs(attempt = 9, protocolError = true))
    }
}
