package com.openautolink.app.transport.aasdk

data class AasdkReconnectDecision(
    val delayMs: Long,
    val extendedBackoff: Boolean,
    val reason: String,
)

object AasdkReconnectPolicy {
    private const val FAST_MIN_MS = 250L
    private const val FAST_SPREAD_MS = 501L
    private const val NORMAL_BASE_MS = 3_000L
    private const val PROTOCOL_BASE_MS = 5_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val FAST_HOTSPOT_ATTEMPTS = 2

    fun decision(
        transportMode: String,
        attempt: Int,
        protocolError: Boolean,
        jitterSeed: Long,
    ): AasdkReconnectDecision {
        val normalizedAttempt = attempt.coerceAtLeast(1)
        val repeatedFailure = normalizedAttempt > FAST_HOTSPOT_ATTEMPTS
        val useFastHotspotRetry = transportMode == "hotspot" && !protocolError && !repeatedFailure

        if (useFastHotspotRetry) {
            val jitter = Math.floorMod(jitterSeed, FAST_SPREAD_MS)
            return AasdkReconnectDecision(
                delayMs = FAST_MIN_MS + jitter,
                extendedBackoff = false,
                reason = "fast wireless retry",
            )
        }

        val baseDelayMs = if (protocolError) PROTOCOL_BASE_MS else NORMAL_BASE_MS
        val exponent = (normalizedAttempt - 1).coerceAtMost(4)
        return AasdkReconnectDecision(
            delayMs = (baseDelayMs * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS),
            extendedBackoff = protocolError || repeatedFailure,
            reason = when {
                protocolError -> "protocol error"
                repeatedFailure -> "repeated failures"
                else -> "standard retry"
            },
        )
    }
}
