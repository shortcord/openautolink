package com.openautolink.app.cluster

import android.content.Intent
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openautolink.app.navigation.ManeuverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GM AAOS cluster session — relays Trip data via NavigationManager.updateTrip().
 *
 * GM has an internal cluster manager (OnStarTurnByTurnManager) that consumes
 * NavigationManager data and renders turn-by-turn on the instrument cluster.
 * The [RelayScreen] is never visible — GM's system ignores it.
 *
 * Primary/secondary multiplexing handles Templates Host creating multiple sessions:
 * - AAOS emulator: creates DISPLAY_TYPE_MAIN + DISPLAY_TYPE_CLUSTER (two sessions)
 * - GM AAOS: creates only DISPLAY_TYPE_MAIN (one session)
 * Only the first (primary) owns NavigationManager. Subsequent sessions are passive.
 */
class ClusterMainSession : Session() {

    companion object {
        private const val TAG = "ClusterMain"

        /** Terminal CPManeuverType values that indicate navigation is complete. */
        private val TERMINAL_TYPES = setOf(
            "destination", "destination_left", "destination_right", "arrive"
        )

        private const val ARRIVAL_TIMEOUT_MS = 10_000L

        @Volatile
        private var primarySession: ClusterMainSession? = null

        /**
         * Proactively end any active cluster navigation. Called from
         * MainActivity.handleUserExit so Templates Host dismisses the cluster
         * Activity (ClusterTurnCardActivity) before our app tasks are killed.
         * Without this, Templates Host keeps the cluster UI on its last frame
         * because our process going away doesn't synchronously notify it.
         */
        fun endActiveNavigation() {
            val session = primarySession ?: return
            try {
                session.navigationManager?.navigationEnded()
                session.isNavigating = false
                Log.i(TAG, "navigationEnded() forced on user exit")
                DiagnosticLog.i("cluster", "navigationEnded() forced on user exit")
            } catch (e: Exception) {
                Log.w(TAG, "endActiveNavigation() failed: ${e.message}")
            }
        }
    }

    private var navigationManager: NavigationManager? = null
    private var isPrimary = false
    private var arrivalTimeoutJob: Job? = null
    private var sessionRegistered = false
    private val sessionScope = CoroutineScope(Dispatchers.Main)

    // Shared navigation delegate — handles state collection, debounce, retry
    private lateinit var delegate: ClusterSessionDelegate

    override fun onCreateScreen(intent: Intent): Screen {
        if (!sessionRegistered) {
            val activeSessions = ClusterBindingState.onSessionCreated()
            sessionRegistered = true
            DiagnosticLog.i("cluster", "Cluster session created; activeSessions=$activeSessions")
        }

        isPrimary = primarySession == null
        if (isPrimary) {
            primarySession = this
            Log.i(TAG, "Primary session created — owns NavigationManager")
            DiagnosticLog.i("cluster", "ClusterMainSession created (primary)")
        } else {
            Log.i(TAG, "Secondary session created — passive")
            return RelayScreen(carContext)
        }

        delegate = ClusterSessionDelegate(
            navigationManager = { navigationManager },
            carContext = { carContext },
        ).apply {
            onManeuverUpdate = { maneuver -> handleManeuverUpdate(maneuver) }
            onNavCleared = {
                retryJob?.cancel()
                retryJob = null
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
            }
            onIdle = {
                retryJob?.cancel()
                retryJob = null
            }
            setup()
        }

        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            Log.i(TAG, "NavigationManager obtained")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get NavigationManager: ${e.message}")
        }

        navigationManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                Log.i(TAG, "onStopNavigation callback from Templates Host")
                try {
                    navigationManager?.navigationStarted()
                    Log.i(TAG, "Re-asserted navigationStarted() after onStopNavigation")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-assert navigationStarted(): ${e.message}")
                    delegate.isNavigating = false
                }
            }

            override fun onAutoDriveEnabled() {
                Log.d(TAG, "Auto drive enabled")
            }
        })

        // Call navigationStarted() IMMEDIATELY — this is the trigger that causes
        // Templates Host to create ClusterTurnCardActivity on the cluster display.
        try {
            navigationManager?.navigationStarted()
            delegate.isNavigating = true
            Log.i(TAG, "navigationStarted() called")
        } catch (e: Exception) {
            Log.w(TAG, "navigationStarted() failed: ${e.message}")
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (isPrimary) {
                    primarySession = null
                    Log.i(TAG, "Primary session destroyed")
                    DiagnosticLog.i("cluster", "ClusterMainSession destroyed")
                    arrivalTimeoutJob?.cancel()
                    arrivalTimeoutJob = null
                    clearManeuverIconCache()
                    if (delegate.isNavigating) {
                        try {
                            navigationManager?.navigationEnded()
                        } catch (e: Exception) {
                            Log.e(TAG, "navigationEnded() failed on destroy: ${e.message}")
                        }
                        delegate.isNavigating = false
                    }
                    delegate.cleanup()
                    navigationManager = null
                }
                if (sessionRegistered) {
                    val activeSessions = ClusterBindingState.onSessionDestroyed()
                    sessionRegistered = false
                    DiagnosticLog.i("cluster", "Cluster session destroyed; activeSessions=$activeSessions")
                }
            }
        })

        return RelayScreen(carContext)
    }

    private fun handleManeuverUpdate(maneuver: ManeuverState) {
        // Arrival timeout for terminal maneuver types
        val maneuverName = maneuver.type.name.lowercase()
        if (TERMINAL_TYPES.any { maneuverName.contains(it) }) {
            if (arrivalTimeoutJob?.isActive != true) {
                arrivalTimeoutJob = sessionScope.launch {
                    delay(ARRIVAL_TIMEOUT_MS)
                    if (delegate.isNavigating) {
                        Log.i(TAG, "Arrival timeout — ending navigation")
                        try { navigationManager?.navigationEnded() } catch (_: Exception) {}
                        delegate.isNavigating = false
                    }
                }
            }
        } else {
            arrivalTimeoutJob?.cancel()
            arrivalTimeoutJob = null
        }
    }

    private class RelayScreen(carContext: CarContext) : Screen(carContext) {
        override fun onGetTemplate(): Template =
            NavigationTemplate.Builder()
                .setNavigationInfo(
                    MessageInfo.Builder("OpenAutoLink — Cluster Navigation")
                        .setText("Cluster navigation service active.")
                        .build()
                )
                .setActionStrip(
                    ActionStrip.Builder().addAction(Action.APP_ICON).build()
                )
                .build()
    }
}
