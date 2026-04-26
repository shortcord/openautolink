package com.openautolink.companion.autostart

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.openautolink.companion.CompanionPrefs
import com.openautolink.companion.service.CompanionService

/**
 * JobService for WiFi-based auto-start. Runs periodically to check if
 * the phone has connected to a target WiFi SSID. Also serves as a
 * reboot-survival mechanism alongside the ConnectivityManager callback.
 */
class WifiJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "WiFi job triggered by OS")
        Thread {
            try {
                checkWifiAndStart(this)
            } catch (e: Exception) {
                Log.e(TAG, "WiFi check failed: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "OAL_WifiJob"
        private const val JOB_ID = 2001

        @Suppress("DEPRECATION")
        fun checkWifiAndStart(context: Context): Boolean {
            val prefs = context.getSharedPreferences(CompanionPrefs.NAME, Context.MODE_PRIVATE)
            val mode = prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0)
            if (mode != CompanionPrefs.AUTO_START_WIFI) return false

            val targetSsids = prefs.getStringSet(CompanionPrefs.AUTO_START_WIFI_SSIDS, emptySet())
                ?: emptySet()
            if (targetSsids.isEmpty()) return false

            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo.ssid?.replace("\"", "") ?: ""
            val currentSsid = if (ssid == "<unknown ssid>") "" else ssid

            Log.d(TAG, "Current SSID='$currentSsid', targets=$targetSsids")

            if (targetSsids.any { it.equals(currentSsid, ignoreCase = true) }) {
                Log.i(TAG, "SSID match — starting service")
                val serviceIntent = Intent(context, CompanionService::class.java).apply {
                    action = CompanionService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Start failed: ${e.message}")
                }
            }
            return false
        }

        fun schedule(context: Context) {
            Log.i(TAG, "Scheduling WiFi auto-start")

            // Modern: ConnectivityManager callback via PendingIntent
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val intent = Intent(context, WifiReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
            try {
                cm.registerNetworkCallback(request, pending)
                Log.i(TAG, "NetworkCallback registered")
            } catch (e: Exception) {
                Log.e(TAG, "NetworkCallback registration failed: ${e.message}")
            }

            // Fallback: periodic JobScheduler
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, WifiJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .build()
            scheduler.schedule(jobInfo)
        }

        fun cancel(context: Context) {
            Log.i(TAG, "Cancelling WiFi auto-start triggers")
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val intent = Intent(context, WifiReceiver::class.java)
            val flags = PendingIntent.FLAG_NO_CREATE or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
            if (pending != null) {
                cm.unregisterNetworkCallback(pending)
            }
        }
    }
}
