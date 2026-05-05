package com.openautolink.app.transport.direct

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.proto.Wireless
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the AA wireless Bluetooth RFCOMM handshake for Direct Mode.
 *
 * Registers an RFCOMM server on the Android Auto UUID. When the phone's BT
 * pairs/connects to the car, it discovers this UUID and initiates the AA
 * wireless handshake:
 *
 *   1. Phone connects RFCOMM -> we accept
 *   2. We send WifiStartRequest (car's IP on the hotspot, port 5288)
 *   3. Phone sends WifiSecurityRequest (type 2) asking for WiFi credentials
 *   4. We send WifiInfoResponse (hotspot SSID, PSK, BSSID)
 *   5. Phone joins/verifies WiFi -> connects TCP:5288 -> AA session starts
 */
class AaBtHandshakeManager(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AaBtHandshake"
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

        private val _status = MutableStateFlow("Idle")
        val status: StateFlow<String> = _status.asStateFlow()
    }

    private var aaServerSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    var hotspotSsid: String = ""
    var hotspotPassword: String = ""
    var hotspotBssid: String = "00:00:00:00:00:00"
    var hotspotIpAddress: String = ""
    var tcpPort: Int = 5288

    @SuppressLint("MissingPermission")
    fun checkCompatibility(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false
        return try {
            val socket = adapter.listenUsingRfcommWithServiceRecord("AA Check", AA_UUID)
            socket.close()
            OalLog.i(TAG, "BT compatibility: AA RFCOMM supported")
            true
        } catch (e: Exception) {
            OalLog.w(TAG, "BT compatibility: FAILED - ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            OalLog.e(TAG, "Bluetooth not available or disabled")
            _status.value = "Bluetooth unavailable"
            return
        }

        isRunning = true
        _status.value = "Listening for phone over Bluetooth"

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("OpenAutoLink AA", AA_UUID)
                OalLog.i(TAG, "Listening on AA UUID")
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept() ?: break
                    OalLog.i(TAG, "Phone connected via BT: ${socket.remoteDevice?.name}")
                    _status.value = "Bluetooth handshake with ${socket.remoteDevice?.name ?: "phone"}"
                    launch(Dispatchers.IO) { handleHandshake(socket) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (isRunning) OalLog.d(TAG, "AA server closed: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        _status.value = "Idle"
        serverJob?.cancel()
        serverJob = null
        try { aaServerSocket?.close() } catch (_: Exception) {}
        aaServerSocket = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleHandshake(socket: BluetoothSocket) {
        try {
            val device = socket.remoteDevice
            OalLog.i(TAG, "Handshake with ${device?.name} (${device?.address})")

            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            if (hotspotSsid.isBlank()) {
                OalLog.e(TAG, "WiFi Direct credentials not ready yet")
                _status.value = "WiFi Direct not ready"
                return
            }

            if (hotspotIpAddress.isBlank()) {
                OalLog.e(TAG, "WiFi Direct IP not ready yet")
                _status.value = "WiFi Direct IP not ready"
                return
            }

            val carIp = hotspotIpAddress
            OalLog.i(TAG, "Car IP: $carIp")

            // Step 1: Wait for phone to send WifiInfoRequest (type 2)
            // The phone initiates the WiFi credential exchange
            OalLog.i(TAG, "Waiting for phone's WifiInfoRequest...")
            val request = readProtobuf(input)
            OalLog.i(TAG, "Received type ${request.type} (${request.payload.size}B)")

            if (request.type == 1) {
                // Phone sent WifiStartRequest first (alternative flow)
                // This happens when the phone already knows the IP from a previous session
                OalLog.i(TAG, "Phone sent WifiStartRequest first — responding with creds")
                // Read the next message which should be type 2
                val request2 = readProtobuf(input)
                OalLog.i(TAG, "Second message type ${request2.type}")
            }

            // Step 2: Send WifiInfoResponse (type 3) with credentials
            val wifiResponse = Wireless.WifiInfoResponse.newBuilder()
                .setSsid(hotspotSsid)
                .setKey(hotspotPassword)
                .setBssid(hotspotBssid)
                .setSecurityMode(
                    if (hotspotPassword.isEmpty()) Wireless.SecurityMode.OPEN
                    else Wireless.SecurityMode.WPA2_PERSONAL
                )
                .setAccessPointType(Wireless.AccessPointType.STATIC)
                .build()
            sendProtobuf(output, wifiResponse.toByteArray(), 3)
            OalLog.i(TAG, "Sent WifiInfoResponse (ssid=$hotspotSsid)")

            // Step 3: Send WifiStartRequest (type 1) with car's IP and port
            val startRequest = Wireless.WifiStartRequest.newBuilder()
                .setIpAddress(carIp)
                .setPort(tcpPort)
                .setStatus(0)
                .build()
            sendProtobuf(output, startRequest.toByteArray(), 1)
            OalLog.i(TAG, "Sent WifiStartRequest (ip=$carIp, port=$tcpPort)")

            // Step 4: Wait for phone's response/status
            OalLog.i(TAG, "BT handshake complete — waiting for phone to connect TCP")
            _status.value = "Waiting for phone WiFi/TCP connect"

            // Keep socket alive during WiFi transition
            while (isRunning && socket.isConnected) {
                delay(2000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OalLog.e(TAG, "Handshake error: ${e.message}")
            _status.value = "Handshake failed: ${e.message ?: "unknown"}"
        } finally {
            try { socket.close() } catch (_: Exception) {}
            OalLog.i(TAG, "BT socket closed")
        }
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Int) {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put((data.size shr 8).toByte())
        buffer.put((data.size and 0xFF).toByte())
        buffer.putShort(type.toShort())
        buffer.put(data)
        output.write(buffer.array())
        output.flush()
    }

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(4)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) {
            ByteArray(size).also { input.readFully(it) }
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    private data class ProtobufMessage(val type: Int, val payload: ByteArray)
}
