package com.openautolink.app.transport.usb

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.openautolink.app.MainActivity
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Invisible activity that receives USB_DEVICE_ATTACHED system intents.
 *
 * When a USB device is plugged in and matches our usb_device_filter.xml,
 * Android delivers the intent here. We forward to MainActivity (which owns
 * the session) and finish immediately.
 */
class UsbAttachedActivity : Activity() {

    companion object {
        private const val TAG = "UsbAttachedActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        val transport = runBlocking {
            AppPreferences.getInstance(this@UsbAttachedActivity).directTransport.first()
        }
        if (transport != "usb") {
            OalLog.i(TAG, "Ignoring USB attach intent because transport=$transport")
            return
        }

        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device == null) {
            OalLog.w(TAG, "USB attach intent missing device extra")
            return
        }
        if (!UsbConstants.isAccessoryDevice(device.vendorId, device.productId) &&
            !UsbDeviceClassifier.isLikelyPhoneCandidate(device)) {
            OalLog.i(TAG, "Ignoring unrelated USB attach: ${UsbDeviceClassifier.describeDevice(device)}")
            return
        }
        OalLog.i(TAG, "USB device attached intent: ${UsbDeviceClassifier.describeDevice(device)}")

        // Launch MainActivity — it's singleTask so this brings it to front
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = UsbManager.ACTION_USB_DEVICE_ATTACHED
            putExtra(UsbManager.EXTRA_DEVICE, device)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(mainIntent)
    }
}
