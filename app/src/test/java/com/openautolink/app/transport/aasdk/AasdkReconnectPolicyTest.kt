package com.openautolink.app.transport.aasdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkReconnectPolicyTest {
    @Test
    fun hotspotUnexpectedDisconnectUsesFastRetry() {
        val decision = AasdkReconnectPolicy.decision(
            transportMode = "hotspot",
            attempt = 1,
            protocolError = false,
            jitterSeed = 0L,
        )

        assertEquals(250L, decision.delayMs)
        assertFalse(decision.extendedBackoff)
        assertEquals("fast wireless retry", decision.reason)
    }

    @Test
    fun hotspotFastRetryIncludesBoundedJitter() {
        val decision = AasdkReconnectPolicy.decision(
            transportMode = "hotspot",
            attempt = 1,
            protocolError = false,
            jitterSeed = 500L,
        )

        assertEquals(750L, decision.delayMs)
        assertFalse(decision.extendedBackoff)
    }

    @Test
    fun protocolErrorUsesExtendedBackoff() {
        val decision = AasdkReconnectPolicy.decision(
            transportMode = "hotspot",
            attempt = 1,
            protocolError = true,
            jitterSeed = 0L,
        )

        assertEquals(5_000L, decision.delayMs)
        assertTrue(decision.extendedBackoff)
        assertEquals("protocol error", decision.reason)
    }

    @Test
    fun repeatedHotspotFailuresLeaveFastRetryAndCap() {
        val third = AasdkReconnectPolicy.decision(
            transportMode = "hotspot",
            attempt = 3,
            protocolError = false,
            jitterSeed = 0L,
        )
        val capped = AasdkReconnectPolicy.decision(
            transportMode = "hotspot",
            attempt = 9,
            protocolError = false,
            jitterSeed = 0L,
        )

        assertEquals(12_000L, third.delayMs)
        assertTrue(third.extendedBackoff)
        assertEquals("repeated failures", third.reason)
        assertEquals(30_000L, capped.delayMs)
    }

    @Test
    fun usbKeepsStandardBackoff() {
        val decision = AasdkReconnectPolicy.decision(
            transportMode = "usb",
            attempt = 1,
            protocolError = false,
            jitterSeed = 0L,
        )

        assertEquals(3_000L, decision.delayMs)
        assertFalse(decision.extendedBackoff)
        assertEquals("standard retry", decision.reason)
    }
}
