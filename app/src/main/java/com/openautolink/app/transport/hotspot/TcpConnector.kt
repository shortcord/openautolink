package com.openautolink.app.transport.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.OalProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP client that connects to the companion app's TCP server on the phone.
 *
 * Discovery strategy (tried in order):
 * 1. mDNS/NSD: discovers `_openautolink._tcp` service (works on any shared network)
 * 2. WiFi gateway IP: connects to DHCP gateway on [COMPANION_PORT] (hotspot mode)
 *
 * On phone hotspot, gateway = phone, so strategy 2 works immediately.
 * On shared WiFi (home/office), strategy 1 finds the phone via mDNS.
 */
class TcpConnector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSocketReady: (Socket) -> Unit,
    /**
     * Optional: called once after a full attempt cycle fails to connect (one
     * tryConnect for manual IP, or all of mDNS+gateway). Used to drive UI
     * escalation (e.g. open the phone picker after N failures) without
     * relying on session-level retries — when the phone is unreachable at
     * the TCP layer we never get an onSessionStopped to count from.
     */
    private val onConnectFailure: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "OAL-TcpConn"
        const val COMPANION_PORT = OalProtocol.AA_PORT
        const val NSD_SERVICE_TYPE = OalProtocol.MDNS_SERVICE_TYPE
        private const val CONNECT_TIMEOUT_MS = 5000
        /**
         * Retry configuration for connection attempts.
         * Exponential backoff: 1s, 3s, 8s (max 15s).
         * Total worst-case retry time: ~22s for 3 retries.
         */
        private const val RETRY_BASE_DELAY_MS = 1000L
        private const val RETRY_MAX_DELAY_MS = 15000L
        private const val MAX_RETRIES = 3
        private const val DISCOVERY_RETRY_MS = 1000L
        /**
         * Throttle for network change detection spam.
         */
        private const val NETWORK_CHANGE_WARN_GAP_MS = 60_000L
    }

    /** When set, connects only to this IP (skips mDNS and gateway discovery). */
    var manualIp: String? = null

    private var connectJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var nsdFoundHost: String? = null

    @Volatile
    private var nsdFoundPort: Int = 0

    /**
     * Last wall-clock ms at which the "no IPv4 interface" warning was emitted.
     */
    @Volatile private var lastNoIfaceWarnMs: Long = 0L

    /**
     * Last gateway IP seen. Used to detect IP address changes.
     */
    @Volatile private var lastGatewayIp: String? = null

    /**
     * Network callback for detecting interface/IP changes.
     */
    private val networkChangeCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: LinkProperties) {
            val newGateway = getGatewayIp()
            if (newGateway != null) {
                val now = System.currentTimeMillis()
                if (now - lastNoIfaceWarnMs > NETWORK_CHANGE_WARN_GAP_MS) {
                    if (newGateway != lastGatewayIp) {
                        lastGatewayIp = newGateway
                        OalLog.i(TAG, "Gateway IP changed: $newGateway")
                        // Trigger re-discovery by clearing cached host/port
                        nsdFoundHost = null
                        nsdFoundPort = 0
                    } else {
                        OalLog.d(TAG, "Gateway IP stable: $newGateway")
                    }
                    lastNoIfaceWarnMs = now
                }
            }
        }
    }

    private fun getGatewayIp(): String? {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            @Suppress("DEPRECATION")
            val dhcp = wifiManager.dhcpInfo ?: return null
            val gateway = dhcp.gateway
            if (gateway == 0) return null
            return "${gateway and 0xFF}.${(gateway shr 8) and 0xFF}.${(gateway shr 16) and 0xFF}.${(gateway shr 24) and 0xFF}"
        } catch (e: Exception) {
            OalLog.e(TAG, "Gateway detection failed: ${e.message}")
            return null
        }
    }

    fun start() {
        if (isRunning) return
        // Reset cached discovery state from any previous connection cycle.
        nsdFoundHost = null
        nsdFoundPort = 0
        lastGatewayIp = null
        lastNoIfaceWarnMs = 0L

        // Register network change callback for IP address change detection
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return  // can't start without connectivity service; isRunning not yet set
            val networkRequest = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(networkRequest, networkChangeCallback)
            OalLog.d(TAG, "Registered network change callback")
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to register network change callback: ${e.message}")
        }

        // Mark running after setup succeeds — early returns above don't leave
        // the connector in a stuck isRunning=true state.
        isRunning = true
        OalLog.i(TAG, "Starting TCP connector (port $COMPANION_PORT)")

        // Manual IP may be either "host" or "host:port". If a port is given it
        // overrides COMPANION_PORT — used by the debug discovery-injection
        // path (DEBUG_INJECT_PHONE), and harmless when omitted in production
        // since every companion listens on the canonical port.
        val raw = manualIp?.takeIf { it.isNotBlank() }
        val manualHost: String?
        val manualPort: Int
        if (raw != null) {
            val colonIdx = raw.lastIndexOf(':')
            val parsedPort = if (colonIdx > 0) raw.substring(colonIdx + 1).toIntOrNull() else null
            if (parsedPort != null && parsedPort in 1..65535 && !raw.contains(']')) {
                manualHost = raw.substring(0, colonIdx)
                manualPort = parsedPort
            } else {
                manualHost = raw
                manualPort = COMPANION_PORT
            }
            OalLog.i(TAG, "Manual IP mode: $manualHost:$manualPort")
        } else {
            manualHost = null
            manualPort = COMPANION_PORT
            startNsdDiscovery()
        }

        connectJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                // Manual IP — skip all discovery, connect directly
                if (manualHost != null) {
                    if (tryConnectWithRetry(manualHost, manualPort, "manual")) return@launch
                    onConnectFailure?.invoke()
                    delay(DISCOVERY_RETRY_MS)
                    continue
                }

                // Try mDNS-discovered host first
                val nsdHost = nsdFoundHost
                var anyTried = false
                if (nsdHost != null && nsdFoundPort > 0) {
                    anyTried = true
                    if (tryConnectWithRetry(nsdHost, nsdFoundPort, "mDNS")) return@launch
                }

                // Fall back to gateway IP (works on phone hotspot)
                val gatewayIp = getGatewayIp()
                if (gatewayIp != null) {
                    anyTried = true
                    if (tryConnectWithRetry(gatewayIp, COMPANION_PORT, "gateway")) return@launch
                } else {
                    OalLog.d(TAG, "No WiFi gateway — waiting for mDNS or network...")
                }

                // Only advance the failure counter when we actually had a target
                // to try. During WiFi blackouts (no gateway, no mDNS), the
                // counter stays steady so the user doesn't see spurious picker
                // escalation.
                if (anyTried) {
                    onConnectFailure?.invoke()
                }
                delay(DISCOVERY_RETRY_MS)
            }
        }
    }

    /**
     * Connect with exponential backoff retry.
     *
     * Retries up to [MAX_RETRIES] times with increasing delays: 1s, 3s, 8s (capped at 15s).
     * Total worst-case retry time: ~22s for 3 retries.
     *
     * @param host target host
     * @param port target port
     * @param source connection source (mDNS, gateway, manual)
     * @return true if connection succeeded
     */
    private suspend fun tryConnectWithRetry(
        host: String,
        port: Int,
        source: String,
    ): Boolean {
        for (attempt in 1..MAX_RETRIES) {
            if (attempt > 1) {
                // Exponential backoff: 1s, 3s, 8s (max 15s)
                // Formula: base * 2^(attempt-2) + base, capped at max
                val delayMs = minOf((RETRY_BASE_DELAY_MS * (1L shl (attempt - 2)) + RETRY_BASE_DELAY_MS), RETRY_MAX_DELAY_MS)
                OalLog.w(
                    TAG,
                    "Connection to $host:$port ($source) failed (attempt $attempt/$MAX_RETRIES) — " +
                        "retrying in ${formatDelay(delayMs)}",
                )
                delay(delayMs)
            } else {
                OalLog.i(TAG, "Connecting to $host:$port ($source)")
            }

            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                // Detect dead phone within ~10s (kernel sends keepalive after idle, then
                // 3 probes 2s apart). Critical for sleep/wake and ungraceful disconnects.
                try {
                    socket.keepAlive = true
                    // Best-effort: low-level setsockopt for tighter timing on Android.
                    // Falls back to OS defaults (~2h idle) if reflection fails.
                    setKeepAliveParams(socket, idleSec = 5, intervalSec = 2, count = 3)
                } catch (e: Exception) {
                    OalLog.d(TAG, "TCP keepalive tuning unavailable: ${e.message}")
                }
                val local = socket.localAddress?.hostAddress ?: "unknown"
                val remote = socket.inetAddress?.hostAddress ?: host
                OalLog.i(
                    TAG,
                    "Connected to companion at $remote:$port ($source), local=$local:${socket.localPort}",
                )
                isRunning = false
                stopNsdDiscovery()
                onSocketReady(socket)
                return true
            } catch (e: Exception) {
                OalLog.d(TAG, "Connection to $host:$port ($source) failed: ${e.message}")
            }
        }
        return false
    }

    private fun formatDelay(ms: Long): String = if (ms < 1000) "${ms}ms" else "${ms / 1000}s"

    private fun startNsdDiscovery() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    OalLog.i(TAG, "mDNS discovery started for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    OalLog.i(TAG, "mDNS service found: ${serviceInfo.serviceName}")
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            OalLog.w(TAG, "mDNS resolve failed: error $errorCode")
                        }

                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val rawHost = si.host
                            // IPv6 link-local addresses (fe80::/10) need a
                            // scope ID (e.g. %wlan0) to connect, which NSD
                            // doesn't surface — connect() returns EINVAL.
                            // Skip them; mDNS will (hopefully) deliver the
                            // IPv4 address on a separate emit.
                            if (rawHost is java.net.Inet6Address && rawHost.isLinkLocalAddress) {
                                OalLog.d(TAG, "Ignoring IPv6 link-local from mDNS: ${rawHost.hostAddress}")
                                return
                            }
                            val host = rawHost?.hostAddress
                            val port = si.port
                            OalLog.i(TAG, "mDNS resolved: $host:$port")
                            if (host != null && port > 0) {
                                nsdFoundHost = host
                                nsdFoundPort = port
                            }
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    OalLog.d(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
                    nsdFoundHost = null
                    nsdFoundPort = 0
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    OalLog.d(TAG, "mDNS discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    OalLog.w(TAG, "mDNS discovery start failed: error $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    OalLog.w(TAG, "mDNS discovery stop failed: error $errorCode")
                }
            }
            nsdManager?.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            OalLog.w(TAG, "mDNS discovery init failed: ${e.message}")
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
    }

    fun stop() {
        OalLog.i(TAG, "Stopping TCP connector")
        isRunning = false
        connectJob?.cancel()
        connectJob = null
        stopNsdDiscovery()
        // Unregister network change callback
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            cm.unregisterNetworkCallback(networkChangeCallback)
        } catch (_: Exception) {
            // May already be unregistered — swallow to avoid leak on next start()
        }
        // Reset cached gateway and warning timestamp so a fresh start() doesn't
        // carry stale state from a previous connection cycle.
        lastGatewayIp = null
        lastNoIfaceWarnMs = 0L
    }

    /**
     * Tune TCP keepalive timing on a connected [Socket].
     *
     * `Socket.setKeepAlive(true)` only enables `SO_KEEPALIVE`; the per-connection
     * timing constants (`TCP_KEEPIDLE` / `TCP_KEEPINTVL` / `TCP_KEEPCNT`) need
     * a native `setsockopt` call. Without these the kernel uses its default
     * 2-hour idle timer which is useless for sub-10s dead-peer detection.
     *
     * Uses [ParcelFileDescriptor.fromSocket] (public API) to dup the underlying
     * FD; socket options set on the dup propagate to the original because both
     * reference the same kernel socket. No hidden-API reflection.
     */
    private fun setKeepAliveParams(socket: Socket, idleSec: Int, intervalSec: Int, count: Int) {
        // Linux kernel ABI constants — stable across all Android versions.
        // Not exposed in OsConstants on Android, but the underlying kernel
        // syscall accepts these directly.
        val IPPROTO_TCP = 6
        val TCP_KEEPIDLE = 4
        val TCP_KEEPINTVL = 5
        val TCP_KEEPCNT = 6
        val pfd = android.os.ParcelFileDescriptor.fromSocket(socket)
        try {
            val fd = pfd.fileDescriptor
            android.system.Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPIDLE, idleSec)
            android.system.Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPINTVL, intervalSec)
            android.system.Os.setsockoptInt(fd, IPPROTO_TCP, TCP_KEEPCNT, count)
        } finally {
            pfd.close()
        }
    }
}
