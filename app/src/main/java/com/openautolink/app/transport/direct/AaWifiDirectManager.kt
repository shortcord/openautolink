package com.openautolink.app.transport.direct

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openautolink.app.diagnostics.OalLog
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Creates and manages a WiFi Direct P2P group for Native AA mode.
 *
 * The car becomes a WiFi Direct Group Owner (mini AP). The phone joins
 * this group using credentials exchanged via the BT RFCOMM handshake.
 *
 * Flow:
 * 1. createGroup() → car creates P2P group
 * 2. onGroupInfoAvailable() → we get SSID, PSK, BSSID, IP
 * 3. Credentials sent to AaBtHandshakeManager
 * 4. Phone connects BT → gets WiFi creds → joins P2P group → TCP:5288
 */
class AaWifiDirectManager(private val context: Context) :
    WifiP2pManager.ConnectionInfoListener,
    WifiP2pManager.GroupInfoListener {

    companion object {
        private const val TAG = "AaWifiDirect"

        private val _status = MutableStateFlow("Idle")
        val status: StateFlow<String> = _status.asStateFlow()
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var groupInfoRetries = 0

    /** Callback when WiFi Direct credentials are ready. */
    var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        OalLog.i(TAG, "P2P connected — requesting connection info")
                        manager?.requestConnectionInfo(channel, this@AaWifiDirectManager)
                    }
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        if (manager == null) {
            OalLog.e(TAG, "WiFi P2P not supported on this device")
            _status.value = "WiFi Direct unsupported"
            return
        }

        isRunning = true
        groupInfoRetries = 0
        channel = manager.initialize(context, context.mainLooper, null)
        _status.value = "Starting WiFi Direct"

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        OalLog.i(TAG, "Starting WiFi Direct group creation...")
        removeGroupAndCreate()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        _status.value = "Idle"
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        manager?.removeGroup(channel, null)
        OalLog.i(TAG, "Stopped")
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndCreate() {
        _status.value = "Resetting old WiFi Direct group"
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                OalLog.d(TAG, "Old group removed")
                delayedCreateGroup(0)
            }
            override fun onFailure(reason: Int) {
                OalLog.d(TAG, "No old group to remove (reason=$reason)")
                delayedCreateGroup(0)
            }
        })
    }

    private fun delayedCreateGroup(retryCount: Int) {
        handler.postDelayed({ createNewGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(retryCount: Int) {
        OalLog.i(TAG, "createGroup attempt ${retryCount + 1}")
        _status.value = "Creating WiFi Direct group (attempt ${retryCount + 1})"
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                OalLog.i(TAG, "P2P Group created successfully!")
                _status.value = "WiFi Direct group created; reading credentials"
                handler.postDelayed({ manager?.requestGroupInfo(channel, this@AaWifiDirectManager) }, 500L)
            }
            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.ERROR -> "ERROR"
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY -> "BUSY"
                    else -> "UNKNOWN($reason)"
                }
                if (reason == WifiP2pManager.BUSY && retryCount < 5) {
                    OalLog.w(TAG, "createGroup BUSY — retrying in 2s (attempt ${retryCount + 1}/5)")
                    _status.value = "WiFi Direct busy; retrying"
                    handler.postDelayed({ createNewGroup(retryCount + 1) }, 2000L)
                } else {
                    OalLog.e(TAG, "createGroup FAILED: $reasonStr")
                    _status.value = "WiFi Direct failed: $reasonStr"
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed && info.isGroupOwner) {
            val goIp = info.groupOwnerAddress?.hostAddress ?: "unknown"
            OalLog.i(TAG, "Group formed — we are Group Owner. IP: $goIp")
            _status.value = "WiFi Direct group formed"
            manager?.requestGroupInfo(channel, this)
        } else if (info.groupFormed) {
            OalLog.w(TAG, "Group formed but we are NOT the owner")
            _status.value = "WiFi Direct group formed on peer"
        }
    }

    @SuppressLint("MissingPermission")
    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        if (group != null) {
            groupInfoRetries = 0
            val ssid = group.networkName
            val psk = group.passphrase ?: ""
            val bssid = getP2pMac(group.`interface`)

            OalLog.i(TAG, "Group info: SSID=$ssid, BSSID=$bssid, interface=${group.`interface`}")
            _status.value = "WiFi Direct credentials readying"

            if (ssid.isNotEmpty()) {
                // Wait for IP to be assigned to the P2P interface
                Thread {
                    try {
                        var ip = getP2pIp(group.`interface`)
                        var retries = 0
                        while (ip == null && retries < 15) {
                            OalLog.d(TAG, "Waiting for IP on ${group.`interface`} (${retries + 1}/15)")
                            _status.value = "Waiting for WiFi Direct IP (${retries + 1}/15)"
                            Thread.sleep(1000)
                            ip = getP2pIp(group.`interface`)
                            retries++
                        }
                        val finalIp = ip ?: "192.168.49.1"
                        OalLog.i(TAG, "WiFi Direct ready: SSID=$ssid IP=$finalIp")
                        _status.value = "WiFi Direct ready"
                        onCredentialsReady?.invoke(ssid, psk, finalIp, bssid)
                    } catch (e: Exception) {
                        OalLog.e(TAG, "Error getting P2P IP: ${e.message}")
                        _status.value = "WiFi Direct IP lookup failed"
                    }
                }.start()
            }
        } else {
            if (groupInfoRetries < 20) {
                groupInfoRetries++
                OalLog.w(TAG, "Group info null — retrying ($groupInfoRetries/20)")
                _status.value = "Waiting for WiFi Direct group info ($groupInfoRetries/20)"
                handler.postDelayed({ manager?.requestGroupInfo(channel, this) }, 1000L)
            } else {
                OalLog.e(TAG, "Group info remained null after 20 retries")
                _status.value = "WiFi Direct group info unavailable"
            }
        }
    }

    private fun getP2pMac(ifaceName: String?): String {
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (ifaceName != null && ni.name != ifaceName) continue
                if (ifaceName == null && !ni.name.contains("p2p")) continue
                val mac = ni.hardwareAddress ?: continue
                return mac.joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) {}
        return "00:00:00:00:00:00"
    }

    private fun getP2pIp(ifaceName: String?): String? {
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (ifaceName != null && ni.name == ifaceName) {
                    for (addr in ni.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                    }
                }
                if (ifaceName == null && ni.name.contains("p2p")) {
                    for (addr in ni.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
