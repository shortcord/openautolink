package com.openautolink.app.cluster

import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * CarAppService for cluster navigation display.
 *
 * Returns either [ClusterMainSession] for GM-style relay hosts or [OalClusterSession]
 * for standard AAOS hosts that render the cluster template directly.
 *
 * GM's internal OnStarTurnByTurnManager consumes NavigationManager Trip data and renders
 * turn-by-turn on the instrument cluster using its own icon set.
 *
 * This service does NOT initialize video, audio, or TCP connections.
 * It only consumes navigation state from [ClusterNavigationState].
 */
class OalClusterService : CarAppService() {

    companion object {
        private const val TAG = "OalClusterSvc"
    }

    override fun createHostValidator(): HostValidator {
        // Do not use ALLOW_ALL_HOSTS_VALIDATOR for the exported service. The
        // platform Templates Host is accepted through android.car.permission
        // .TEMPLATE_RENDERER, and local/emulator system bindings are accepted by
        // HostValidator itself. Unknown third-party binders are rejected.
        return HostValidator.Builder(this).build()
    }

    @Suppress("DEPRECATION")
    override fun onCreateSession(): Session {
        Log.i(TAG, "Creating session (no SessionInfo — fallback)")
        return ClusterMainSession()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i(TAG, "Creating session: displayType=${sessionInfo.displayType}")
        return if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) {
            OalClusterSession()
        } else {
            ClusterMainSession()
        }
    }
}
