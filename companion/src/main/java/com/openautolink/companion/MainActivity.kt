package com.openautolink.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.openautolink.companion.service.CompanionService
import com.openautolink.companion.ui.MainScreen
import com.openautolink.companion.ui.theme.OalCompanionTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN,
    ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted/denied — UI will reflect state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMissingPermissions()
        checkBatteryOptimization()
        handleIntent(intent)

        val prefs = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
        val autoStartMode = prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0)
        if (autoStartMode == CompanionPrefs.AUTO_START_APP_OPEN && !CompanionService.isRunning.value) {
            startCompanionService()
        }

        setContent {
            OalCompanionTheme {
                MainScreen(
                    onStart = { startCompanionService() },
                    onStop = { stopCompanionService() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "oalcompanion") return
        when (uri.host) {
            "start" -> startCompanionService()
            "stop" -> stopCompanionService()
        }
    }

    private fun startCompanionService() {
        val serviceIntent = Intent(this, CompanionService::class.java).apply {
            action = CompanionService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopCompanionService() {
        val serviceIntent = Intent(this, CompanionService::class.java).apply {
            action = CompanionService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage(
                    "OpenAutoLink Companion needs to run in the background to maintain " +
                    "the connection to your car. Please disable battery optimization for this app."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
