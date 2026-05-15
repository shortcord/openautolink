package com.openautolink.app.ui.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHotspotResolvePolicyTest {
    @Test
    fun manualConnectKeepsChooserOrientedBudget() {
        val budget = CarHotspotResolvePolicy.budget(CarHotspotConnectIntent.MANUAL)

        assertEquals(45_000L, budget.timeoutMs)
        assertTrue(budget.showChooserOnFailure)
        assertTrue(budget.allowManualIpFallback)
        assertEquals("manual", budget.logLabel)
    }

    @Test
    fun automaticReconnectUsesShortDiscoveryBudget() {
        val budget = CarHotspotResolvePolicy.budget(CarHotspotConnectIntent.AUTOMATIC_RECONNECT)

        assertEquals(6_000L, budget.timeoutMs)
        assertFalse(budget.showChooserOnFailure)
        assertFalse(budget.allowManualIpFallback)
        assertEquals("auto-reconnect", budget.logLabel)
    }
}
