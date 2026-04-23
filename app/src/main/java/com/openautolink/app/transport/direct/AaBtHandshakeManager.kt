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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.UUID

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
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    }

    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    var hotspotSsid: String = ""
    var hotspotPassword: String = ""

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
        isRunning = true

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            OalLog.e(TAG, "Bluetooth not available or disabled")
            return
        }

        serverJob = scope.launch(Dispatchers.IO) {
            launch {
                try {
                    aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("OpenAutoLink AA", AA_UUID)
                    OalLog.i(TAG, "Listening on AA UUID")
                    while (isRunning && isActive) {
                        val socket = aaServerSocket?.accept() ?: break
                        OalLog.i(TAG, "Phone connected via BT: ${socket.remoteDevice?.name}")
                        launch(Dispatchers.IO) { handleHandshake(socket) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (isRunning) OalLog.d(TAG, "AA server closed: ${e.message}")
                }
            }

            launch {
                try {
                    hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("OpenAutoLink HFP", HFP_UUID)
                    while (isRunning && isActive) {
                        val socket = hfpServerSocket?.accept() ?: break
                        launch(Dispatchers.IO) {
                            try { socket.inputStream.read(ByteArray(1024)) }
                            catch (_: Exception) {}
                            finally { try { socket.close() } catch (_: Exception) {} }
                        }
                    }
                } catch (_: IOException) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        serverJob = null
        try { aaServerSocket?.close() } catch (_: Exception) {}
        try { hfpServerSocket?.close() } catch (_: Exception) {}
        aaServerSocket = null
        hfpServerSocket = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleHandshake(socket: BluetoothSocket) {
        try {
            val device = socket.remoteDevice
            OalLog.i(TAG, "Handshake with ${device?.name} (${device?.address})")

            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            val carIp = findCarIp() ?: run {
                OalLog.e(TAG, "No car IP on WiFi - is car connected to phone hotspot?")
                return
            }
            OalLog.i(TAG, "Car IP: $carIp")

            // Send WifiStartRequest
            val startRequest = Wireless.WifiStartRequest.newBuilder()
                .setIpAddress(carIp)
                .setPort(5288)
                .setStatus(0)
                .build()
            sendProtobuf(output, startRequest.toByteArray(), 1)
            OalLog.i(TAG, "Sent WifiStartRequest (ip=$carIp, port=5288)")

            // Read response (type 2 = WifiSecurityRequest)
            val response = readProtobuf(input)
            OalLog.i(TAG, "Response type ${response.type} (${response.payload.size}B)")

            if (response.type == 2) {
                // Send WifiInfoResponse with hotspot creds
                val wifiResponse = Wireless.WifiInfoResponse.newBuilder()
                    .setSsid(hotspotSsid)
                    .setKey(hotspotPassword)
                    .setBssid("00:00:00:00:00:00")
                    .setSecurityMode(
                        if (hotspotPassword.isEmpty()) Wireless.SecurityMode.OPEN
                        else Wireless.SecurityMode.WPA2_PERSONAL
                    )
                    .setAccessPointType(Wireless.AccessPointType.STATIC)
                    .build()
                sendProtobuf(output, wifiResponse.toByteArray(), 3)
                OalLog.i(TAG, "Sent WifiInfoResponse (ssid=$hotspotSsid)")

                // Keep socket alive during WiFi transition
                while (isRunning && socket.isConnected) {
                    delay(2000)
                }
            } else {
                OalLog.w(TAG, "Unexpected response type: ${response.type} (expected 2)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OalLog.e(TAG, "Handshake error: ${e.message}")
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

    private fun findCarIp(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.name.startsWith("wlan")) continue
                for (addr in ni.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private data class ProtobufMessage(val type: Int, val payload: ByteArray)
}
