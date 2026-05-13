package com.openautolink.app.ui.projection

internal enum class CarHotspotConnectIntent {
    MANUAL,
    AUTOMATIC_RECONNECT,
}

internal data class CarHotspotResolveBudget(
    val timeoutMs: Long,
    val showChooserOnFailure: Boolean,
    val allowManualIpFallback: Boolean,
    val logLabel: String,
)

internal object CarHotspotResolvePolicy {
    const val MANUAL_TIMEOUT_MS = 45_000L
    const val AUTO_RECONNECT_TIMEOUT_MS = 6_000L

    fun budget(intent: CarHotspotConnectIntent): CarHotspotResolveBudget =
        when (intent) {
            CarHotspotConnectIntent.MANUAL -> CarHotspotResolveBudget(
                timeoutMs = MANUAL_TIMEOUT_MS,
                showChooserOnFailure = true,
                allowManualIpFallback = true,
                logLabel = "manual",
            )
            CarHotspotConnectIntent.AUTOMATIC_RECONNECT -> CarHotspotResolveBudget(
                timeoutMs = AUTO_RECONNECT_TIMEOUT_MS,
                showChooserOnFailure = false,
                allowManualIpFallback = false,
                logLabel = "auto-reconnect",
            )
        }
}
