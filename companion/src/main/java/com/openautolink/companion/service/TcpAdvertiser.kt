package com.openautolink.companion.service

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.openautolink.companion.connection.AaProxy
import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.trigger.TransparentTriggerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP transport for car ←→ phone AA connection.
 *
 * Listens on [PORT] for incoming TCP connections from the car app.
 * Also registers an mDNS/NSD service so the car can discover the phone
 * on any shared network (hotspot or home WiFi).
 */
class TcpAdvertiser(
    private val context: Context,
    private val stateListener: NearbyAdvertiser.StateListener,
) {
    companion object {
        private const val TAG = "OAL_TcpAdv"
        const val PORT = 5277
        const val NSD_SERVICE_TYPE = "_openautolink._tcp"
        const val NSD_SERVICE_NAME = "OpenAutoLink"

        /** How long we wait for Google AA to connect to our proxy before retrying. */
        private const val AA_CONNECT_TIMEOUT_MS = 8_000L
        /** Re-fire the AA launch intent up to this many times before resetting the car socket. */
        private const val MAX_AA_LAUNCH_RETRIES = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var activeProxy: AaProxy? = null
    private var activeCarSocket: Socket? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var aaConnectWatchdog: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isLaunching = false

    /** Number of times we've re-launched AA without it connecting back to the proxy. */
    @Volatile
    private var aaLaunchAttempts = 0

    fun start() {
        if (isRunning) return
        isRunning = true
        CompanionLog.i(TAG, "Starting TCP server on port $PORT")

        scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                serverSocket = server
                CompanionLog.i(TAG, "Listening on 0.0.0.0:$PORT")

                registerNsd()

                while (isRunning) {
                    val carSocket = server.accept()
                    CompanionLog.i(TAG, "Car connected from ${carSocket.inetAddress.hostAddress}")
                    stateListener.onConnecting()
                    handleCarConnection(carSocket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    CompanionLog.e(TAG, "TCP server error: ${e.message}")
                }
            }
        }
    }

    private fun handleCarConnection(carSocket: Socket) {
        // Close any previous session
        activeProxy?.stop()
        activeCarSocket?.let { runCatching { it.close() } }
        activeCarSocket = carSocket
        isLaunching = false
        aaLaunchAttempts = 0

        launchAndroidAuto(carSocket)
    }

    private fun launchAndroidAuto(carSocket: Socket) {
        if (isLaunching) return
        isLaunching = true

        scope.launch {
            try {
                val proxy = AaProxy(
                    preConnectedSocket = carSocket,
                    listener = object : AaProxy.Listener {
                        override fun onConnected() {
                            CompanionLog.i(TAG, "AA flowing through TCP proxy")
                            // AA is alive — cancel the watchdog and reset retry counter.
                            aaConnectWatchdog?.cancel()
                            aaConnectWatchdog = null
                            aaLaunchAttempts = 0
                            stateListener.onProxyConnected()
                        }

                        override fun onDisconnected() {
                            CompanionLog.i(TAG, "AA TCP proxy disconnected")
                            stateListener.onProxyDisconnected()
                            // Re-accept next connection
                            isLaunching = false
                        }
                    },
                )
                activeProxy = proxy
                val localPort = proxy.start()

                fireAaLaunchIntent(localPort)
                startAaConnectWatchdog(carSocket)
            } catch (e: Exception) {
                CompanionLog.e(TAG, "Failed to launch AA: ${e.message}")
                isLaunching = false
                stateListener.onProxyDisconnected()
            }
        }
    }

    private fun fireAaLaunchIntent(localPort: Int) {
        val aaIntent = Intent().apply {
            setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            putExtra("PARAM_SERVICE_PORT", localPort)
            putExtra("ip_address", "127.0.0.1")
            putExtra("projection_port", localPort)
        }

        CompanionLog.i(TAG, "Launching AA via TransparentTrigger, proxy port=$localPort")
        val triggerIntent =
            Intent(context, TransparentTriggerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("intent", aaIntent)
            }
        context.startActivity(triggerIntent)
    }

    /**
     * If Google AA doesn't connect to our proxy within [AA_CONNECT_TIMEOUT_MS]
     * the AA app on the phone is dead/stuck/in-the-wrong-state. Attempt
     * recovery without requiring the user to tap Stop+Start:
     *   - Retry the launch intent up to [MAX_AA_LAUNCH_RETRIES] times.
     *   - If that still fails, force-close the car socket so the car-side
     *     reconnects with a fresh TCP session and we start over.
     */
    private fun startAaConnectWatchdog(carSocket: Socket) {
        aaConnectWatchdog?.cancel()
        aaConnectWatchdog = scope.launch {
            delay(AA_CONNECT_TIMEOUT_MS)
            val proxy = activeProxy
            if (proxy == null || proxy.hasActiveBridge() || !isRunning) return@launch
            aaLaunchAttempts++
            if (aaLaunchAttempts <= MAX_AA_LAUNCH_RETRIES) {
                CompanionLog.w(TAG,
                    "AA didn't connect to proxy in ${AA_CONNECT_TIMEOUT_MS}ms — retry #$aaLaunchAttempts")
                val port = proxy.localPort
                if (port > 0) fireAaLaunchIntent(port)
                startAaConnectWatchdog(carSocket)
            } else {
                CompanionLog.w(TAG,
                    "AA never connected after $MAX_AA_LAUNCH_RETRIES retries — force-resetting car socket")
                aaLaunchAttempts = 0
                runCatching { carSocket.close() }
                activeProxy?.stop()
                activeProxy = null
                activeCarSocket = null
                isLaunching = false
                stateListener.onProxyDisconnected()
            }
        }
    }

    fun stop() {
        CompanionLog.i(TAG, "Stopping TCP server")
        isRunning = false
        aaConnectWatchdog?.cancel()
        aaConnectWatchdog = null
        unregisterNsd()
        activeProxy?.stop()
        activeProxy = null
        activeCarSocket?.let { runCatching { it.close() } }
        activeCarSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                port = PORT
            }
            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                    CompanionLog.w(TAG, "mDNS registration failed: error $errorCode")
                }
                override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                    CompanionLog.w(TAG, "mDNS unregistration failed: error $errorCode")
                }
                override fun onServiceRegistered(si: NsdServiceInfo) {
                    CompanionLog.i(TAG, "mDNS registered: ${si.serviceName} on port $PORT")
                }
                override fun onServiceUnregistered(si: NsdServiceInfo) {
                    CompanionLog.d(TAG, "mDNS unregistered")
                }
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            CompanionLog.w(TAG, "mDNS registration failed: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        nsdRegistrationListener = null
    }
}
