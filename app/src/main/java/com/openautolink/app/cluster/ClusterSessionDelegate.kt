package com.openautolink.app.cluster

import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.navigation.ManeuverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shared cluster navigation logic extracted from [ClusterMainSession] and [OalClusterSession].
 *
 * Both sessions follow the same pattern:
 * 1. Collect [ClusterNavigationState.state] with a 200ms debounce
 * 2. Call [navigationStarted] / [updateTrip] / [navigationEnded] on NavigationManager
 * 3. Retry with exponential backoff on transient failures
 *
 * Used via composition — each session creates a [ClusterSessionDelegate] and
 * calls through it, rather than duplicating the state-collection + retry logic.
 */
class ClusterSessionDelegate(
    private val navigationManager: () -> androidx.car.app.navigation.NavigationManager?,
    private val carContext: () -> androidx.car.app.CarContext,
) {
    companion object {
        private const val TAG = "ClusterSessionDelegate"
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    @Volatile
    var isNavigating = false
    var hasSeenActiveNav = false

    private var scope: CoroutineScope? = null
    private var retryJob: Job? = null

    /** Callback when a non-null maneuver is processed (after NavigationManager update). */
    var onManeuverUpdate: ((ManeuverState) -> Unit)? = null

    /** Callback when navigation is cleared. */
    var onNavCleared: (() -> Unit)? = null

    /** Callback when state is null but no active nav seen yet. */
    var onIdle: (() -> Unit)? = null

    fun setup() {
        scope = CoroutineScope(Dispatchers.Main)
        scope?.launch { collectNavigationState() }
    }

    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null
        ClusterNavigationState.state.collectLatest { maneuver ->
            debounceJob?.cancel()
            debounceJob = scope?.launch {
                delay(200)
                processStateUpdate(maneuver)
            }
        }
    }

    private fun processStateUpdate(maneuver: ManeuverState?) {
        processStateUpdate(maneuver, retryAttempt = 0)
    }

    private fun processStateUpdate(maneuver: ManeuverState?, retryAttempt: Int) {
        val navManager = navigationManager() ?: return

        if (maneuver != null) {
            hasSeenActiveNav = true

            if (!isNavigating) {
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                    Log.i(TAG, "navigationStarted() (re-start)")
                } catch (e: Exception) {
                    Log.e(TAG, "navigationStarted() failed: ${e.message}")
                    DiagnosticLog.e("cluster", "navigationStarted() failed: ${e.message}")
                    scheduleRetry(maneuver, retryAttempt)
                    return
                }
            }

            try {
                val trip = buildClusterTrip(maneuver, carContext())
                navManager.updateTrip(trip)
                DiagnosticLog.d("cluster", "Trip: ${maneuver.type} dist=${maneuver.distanceMeters}m road=${maneuver.roadName} lanes=${maneuver.lanes?.size ?: 0}")
            } catch (e: Exception) {
                Log.e(TAG, "updateTrip() failed: ${e.message}")
                DiagnosticLog.e("cluster", "updateTrip() failed: ${e.message}")
                scheduleRetry(maneuver, retryAttempt)
                return
            }

            retryJob?.cancel()
            retryJob = null
            onManeuverUpdate?.invoke(maneuver)
        } else if (isNavigating && hasSeenActiveNav) {
            retryJob?.cancel()
            retryJob = null
            Log.i(TAG, "navigationEnded() — nav cleared")
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                Log.e(TAG, "navigationEnded() failed: ${e.message}")
            }
            isNavigating = false
            onNavCleared?.invoke()
        } else {
            retryJob?.cancel()
            retryJob = null
            onIdle?.invoke()
        }
    }

    private fun scheduleRetry(maneuver: ManeuverState, retryAttempt: Int) {
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Cluster update retry limit reached")
            return
        }
        retryJob?.cancel()
        retryJob = scope?.launch {
            delay(RETRY_DELAY_MS)
            if (ClusterNavigationState.state.value == maneuver) {
                processStateUpdate(maneuver, retryAttempt + 1)
            }
        }
    }

    fun cleanup() {
        retryJob?.cancel()
        retryJob = null
        scope?.cancel()
        scope = null
    }

    /**
     * Cancel any pending retry job. Callers should use this when they need to
     * interrupt an in-flight retry — the delegate also cancels before invoking
     * the nav-cleared/idle callbacks, so this is only needed from outside
     * (e.g. force-end on user exit).
     */
    fun cancelRetryJob() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * End the active navigation: call [NavigationManager.navigationEnded]
     * and reset [isNavigating]. Safe to call multiple times — the second
     * call short-circuits when the delegate isn't navigating. Used by
     * [ClusterMainSession.endActiveNavigation] for the user-exit path and
     * by the session's `onDestroy`.
     */
    fun endNavigation() {
        if (!isNavigating) return
        val navManager = navigationManager() ?: run {
            isNavigating = false
            return
        }
        try {
            navManager.navigationEnded()
        } catch (e: Exception) {
            Log.e(TAG, "navigationEnded() failed: ${e.message}")
        }
        isNavigating = false
    }
}
