package com.openautolink.app.transport.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.openautolink.app.diagnostics.OalLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wraps USB bulk endpoints as InputStream/OutputStream for use with AasdkTransportPipe.
 *
 * The InputStream blocks on bulkTransfer(IN) until data arrives.
 * The OutputStream calls bulkTransfer(OUT) to push data.
 *
 * Thread safety: same model as AasdkTransportPipe — read and write operate on
 * independent endpoints and are safe from separate threads.
 */
class UsbTransportPipe(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) {
    companion object {
        private const val TAG = "UsbTransportPipe"
        private const val BULK_TIMEOUT_MS = 1000
    }

    @Volatile
    private var closed = false

    fun toInputStream(): InputStream = UsbInputStream()
    fun toOutputStream(): OutputStream = UsbOutputStream()

    fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (e: Exception) {
            OalLog.w(TAG, "Error closing USB connection: ${e.message}")
        }
    }

    private inner class UsbInputStream : InputStream() {
        override fun read(): Int {
            val buf = ByteArray(1)
            val result = read(buf, 0, 1)
            if (result <= 0) return -1
            return buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("USB pipe closed")
            // bulkTransfer requires offset=0, so use a temp buffer when off > 0
            val buffer = if (off == 0) b else ByteArray(len)
            while (!closed) {
                val result = connection.bulkTransfer(endpointIn, buffer, len, BULK_TIMEOUT_MS)
                if (result > 0) {
                    if (off > 0) System.arraycopy(buffer, 0, b, off, result)
                    return result
                }
                if (result < 0 && closed) throw IOException("USB pipe closed")
                // result == 0 or timeout: retry
            }
            throw IOException("USB pipe closed")
        }

        override fun close() {
            this@UsbTransportPipe.close()
        }
    }

    private inner class UsbOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("USB pipe closed")
            val buffer = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            var sent = 0
            while (sent < len) {
                if (closed) throw IOException("USB pipe closed")
                val chunk = minOf(len - sent, endpointOut.maxPacketSize)
                val data = if (sent == 0 && chunk == buffer.size) buffer
                           else buffer.copyOfRange(sent, sent + chunk)
                val result = connection.bulkTransfer(endpointOut, data, data.size, BULK_TIMEOUT_MS)
                if (result < 0) throw IOException("USB bulk write failed: $result")
                sent += result
            }
        }

        override fun flush() {
            // USB bulk transfers are flushed immediately
        }

        override fun close() {
            this@UsbTransportPipe.close()
        }
    }
}
