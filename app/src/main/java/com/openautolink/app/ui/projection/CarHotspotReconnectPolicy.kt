package com.openautolink.app.ui.projection

internal object CarHotspotReconnectPolicy {
    fun delayMs(attempt: Int, protocolError: Boolean): Long {
        val normalizedAttempt = attempt.coerceAtLeast(1)
        if (protocolError) {
            val exponent = (normalizedAttempt - 1).coerceAtMost(4)
            return (5_000L * (1L shl exponent)).coerceAtMost(30_000L)
        }
        if (normalizedAttempt <= 2) return 500L
        val exponent = (normalizedAttempt - 1).coerceAtMost(4)
        return (3_000L * (1L shl exponent)).coerceAtMost(30_000L)
    }
}
