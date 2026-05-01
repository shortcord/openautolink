package com.openautolink.companion.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openautolink.companion.R
import com.openautolink.companion.CompanionPrefs
import com.openautolink.companion.autostart.WifiJobService
import com.openautolink.companion.service.CompanionService
import com.openautolink.companion.ui.theme.OalGreen
import com.openautolink.companion.ui.theme.OalOrange
import com.openautolink.companion.ui.theme.OalRed

@Composable
fun MainScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(CompanionPrefs.NAME, Context.MODE_PRIVATE)
    }

    val isRunning by CompanionService.isRunning.collectAsState()
    val isConnected by CompanionService.isConnected.collectAsState()
    val statusText by CompanionService.statusText.collectAsState()

    var autoStartMode by remember {
        mutableIntStateOf(prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0))
    }
    var selectedBtMacs by remember {
        mutableStateOf(prefs.getStringSet(CompanionPrefs.AUTO_START_BT_MACS, emptySet())
            ?: emptySet())
    }
    var stopOnBtDisconnect by remember {
        mutableStateOf(prefs.getBoolean(CompanionPrefs.BT_DISCONNECT_STOP, false))
    }
    var autoReconnect by remember {
        mutableStateOf(prefs.getBoolean(CompanionPrefs.BT_AUTO_RECONNECT, false))
    }
    var wifiSsids by remember {
        mutableStateOf(
            prefs.getStringSet(CompanionPrefs.AUTO_START_WIFI_SSIDS, emptySet())
                ?.toSet() ?: emptySet()
        )
    }
    var stopOnWifiDisconnect by remember {
        mutableStateOf(prefs.getBoolean(CompanionPrefs.WIFI_DISCONNECT_STOP, false))
    }

    fun saveAutoStartMode(mode: Int) {
        autoStartMode = mode
        prefs.edit().putInt(CompanionPrefs.AUTO_START_MODE, mode).apply()

        // Schedule/cancel WiFi monitoring
        if (mode == CompanionPrefs.AUTO_START_WIFI) {
            WifiJobService.schedule(context)
        } else {
            WifiJobService.cancel(context)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Header ─────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val radius = size.minDimension / 2f
                        val brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to androidx.compose.ui.graphics.Color.Black,
                                0.65f to androidx.compose.ui.graphics.Color.Black,
                                1.0f to androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = radius,
                        )
                        onDrawWithContent {
                            drawContent()
                            drawCircle(brush = brush, blendMode = BlendMode.DstIn)
                        }
                    },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "OpenAutoLink",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(24.dp))

            // ── Status Card ────────────────────────────────────────
            StatusCard(isRunning, isConnected, statusText)

            Spacer(Modifier.height(16.dp))

            // ── Start / Stop ───────────────────────────────────────
            val buttonColor by animateColorAsState(
                targetValue = if (isRunning) OalRed else OalGreen,
                label = "button",
            )
            Button(
                onClick = { if (isRunning) onStop() else onStart() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "Stop" else "Start",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            // ── Connection Mode ─────────────────────────────────────
            Text(
                text = "Connection Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            var connectionMode by remember {
                mutableStateOf(
                    prefs.getString(
                        CompanionPrefs.CONNECTION_MODE,
                        CompanionPrefs.DEFAULT_CONNECTION_MODE,
                    ) ?: CompanionPrefs.DEFAULT_CONNECTION_MODE
                )
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = connectionMode == CompanionPrefs.MODE_PHONE_HOTSPOT,
                    onClick = {
                        connectionMode = CompanionPrefs.MODE_PHONE_HOTSPOT
                        prefs.edit().putString(
                            CompanionPrefs.CONNECTION_MODE,
                            CompanionPrefs.MODE_PHONE_HOTSPOT,
                        ).apply()
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Phone Hotspot") }
                SegmentedButton(
                    selected = connectionMode == CompanionPrefs.MODE_CAR_HOTSPOT,
                    onClick = {
                        connectionMode = CompanionPrefs.MODE_CAR_HOTSPOT
                        prefs.edit().putString(
                            CompanionPrefs.CONNECTION_MODE,
                            CompanionPrefs.MODE_CAR_HOTSPOT,
                        ).apply()
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Car Hotspot") }
            }
            Text(
                text = when (connectionMode) {
                    CompanionPrefs.MODE_PHONE_HOTSPOT ->
                        "This phone hosts the WiFi hotspot. The car connects to this phone."
                    CompanionPrefs.MODE_CAR_HOTSPOT ->
                        "Connect this phone to the car's WiFi hotspot. Multiple phones can be connected at once; the car-side app picks the active phone via the floating switcher button."
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // Phone identity — visible so users know what they look like to
            // the car app. Friendly name is editable; phone_id is read-only.
            Spacer(Modifier.height(12.dp))
            val phoneIdShort = remember {
                CompanionPrefs.getOrCreatePhoneId(prefs).take(8)
            }
            var friendlyName by remember {
                mutableStateOf(CompanionPrefs.getFriendlyName(prefs))
            }
            Text("Phone identity", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.OutlinedTextField(
                value = friendlyName,
                onValueChange = {
                    friendlyName = it
                    prefs.edit().putString(CompanionPrefs.PHONE_FRIENDLY_NAME, it).apply()
                },
                label = { Text("Friendly name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "ID: $phoneIdShort  (auto-generated, used by the car app to remember this phone)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            // ── Auto-Start Section ─────────────────────────────────
            Text(
                text = "Auto-Start",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            AutoStartModeSelector(autoStartMode) { saveAutoStartMode(it) }

            Spacer(Modifier.height(16.dp))

            // ── BT Config ──────────────────────────────────────────
            if (autoStartMode == CompanionPrefs.AUTO_START_BT) {
                BtAutoStartConfig(
                    selectedMacs = selectedBtMacs,
                    onMacsChanged = { macs ->
                        selectedBtMacs = macs
                        prefs.edit()
                            .putStringSet(CompanionPrefs.AUTO_START_BT_MACS, macs)
                            .apply()
                    },
                    stopOnDisconnect = stopOnBtDisconnect,
                    onStopOnDisconnectChanged = { v ->
                        stopOnBtDisconnect = v
                        prefs.edit()
                            .putBoolean(CompanionPrefs.BT_DISCONNECT_STOP, v)
                            .apply()
                    },
                    autoReconnect = autoReconnect,
                    onAutoReconnectChanged = { v ->
                        autoReconnect = v
                        prefs.edit()
                            .putBoolean(CompanionPrefs.BT_AUTO_RECONNECT, v)
                            .apply()
                    },
                )
            }

            // ── WiFi Config ────────────────────────────────────────
            if (autoStartMode == CompanionPrefs.AUTO_START_WIFI) {
                WifiAutoStartConfig(
                    ssids = wifiSsids,
                    onSsidsChanged = { newSet ->
                        wifiSsids = newSet
                        prefs.edit()
                            .putStringSet(CompanionPrefs.AUTO_START_WIFI_SSIDS, newSet)
                            .apply()
                    },
                    stopOnDisconnect = stopOnWifiDisconnect,
                    onStopOnDisconnectChanged = { v ->
                        stopOnWifiDisconnect = v
                        prefs.edit()
                            .putBoolean(CompanionPrefs.WIFI_DISCONNECT_STOP, v)
                            .apply()
                    },
                )
            }

            if (autoStartMode == CompanionPrefs.AUTO_START_APP_OPEN) {
                Text(
                    text = "Service will start automatically when the app is opened.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            // ── File Logging ───────────────────────────────────────
            FileLoggingSection()

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            // ── Deep Link Info ─────────────────────────────────────
            Text(
                text = "Deep Links",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "oalcompanion://start — Start advertising\noalcompanion://stop — Stop service",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Status Card ────────────────────────────────────────────────────────

@Composable
private fun StatusCard(isRunning: Boolean, isConnected: Boolean, statusText: String) {
    val statusColor by animateColorAsState(
        targetValue = when {
            isConnected -> OalGreen
            isRunning -> OalOrange
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "status",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRunning && !isConnected) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                Icon(
                    imageVector = if (isConnected) Icons.Default.CheckCircle
                    else Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = statusColor,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                )
                if (isRunning && !isConnected) {
                    Text(
                        text = "Waiting for car to discover this phone...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Auto-Start Mode Selector ───────────────────────────────────────────

@Composable
private fun AutoStartModeSelector(selected: Int, onSelected: (Int) -> Unit) {
    val modes = listOf("Off", "Bluetooth", "WiFi", "App Open")

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selected == index,
                onClick = { onSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

// ── BT Auto-Start Config ───────────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
private fun BtAutoStartConfig(
    selectedMacs: Set<String>,
    onMacsChanged: (Set<String>) -> Unit,
    stopOnDisconnect: Boolean,
    onStopOnDisconnectChanged: (Boolean) -> Unit,
    autoReconnect: Boolean,
    onAutoReconnectChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showDevicePicker by remember { mutableStateOf(false) }

    val pairedDevices = remember {
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bondedDevices?.map { it.address to (it.name ?: it.address) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Bluetooth Trigger",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            if (selectedMacs.isEmpty()) {
                Text(
                    text = "No devices selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                selectedMacs.forEach { mac ->
                    val name = pairedDevices.firstOrNull { it.first == mac }?.second ?: mac
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bluetooth, null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showDevicePicker = true }) {
                Text("Select Devices")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SwitchRow("Stop on BT disconnect", stopOnDisconnect, onStopOnDisconnectChanged)
            SwitchRow("Auto-reconnect", autoReconnect, onAutoReconnectChanged)
        }
    }

    if (showDevicePicker) {
        BtDevicePickerDialog(
            pairedDevices = pairedDevices,
            selectedMacs = selectedMacs,
            onDismiss = { showDevicePicker = false },
            onConfirm = { macs ->
                onMacsChanged(macs)
                showDevicePicker = false
            },
        )
    }
}

@Composable
private fun BtDevicePickerDialog(
    pairedDevices: List<Pair<String, String>>,
    selectedMacs: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selection by remember { mutableStateOf(selectedMacs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Bluetooth Devices") },
        text = {
            Column {
                if (pairedDevices.isEmpty()) {
                    Text("No paired Bluetooth devices found.")
                } else {
                    pairedDevices.forEach { (mac, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = selection.contains(mac),
                                onCheckedChange = { checked ->
                                    selection = if (checked) selection + mac
                                    else selection - mac
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── WiFi Auto-Start Config ─────────────────────────────────────────────

@Composable
private fun WifiAutoStartConfig(
    ssids: Set<String>,
    onSsidsChanged: (Set<String>) -> Unit,
    stopOnDisconnect: Boolean,
    onStopOnDisconnectChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val wifi = remember {
        context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
    }

    // Visible networks from a recent scan. Triggered explicitly via the
    // refresh button — Android throttles background scans heavily so we
    // never auto-rescan.
    var scanResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(false) }

    // Currently-connected SSID, useful as a quick "add this one" affordance.
    val currentSsid: String? = remember(scanResults) { resolveCurrentSsid(wifi) }

    // Manual-entry field for SSIDs that aren't currently in scan range.
    var manualEntry by remember { mutableStateOf("") }

    fun runScan() {
        if (wifi == null) {
            scanError = "WiFi unavailable"
            return
        }
        isScanning = true
        scanError = null
        try {
            // Start a scan; results may come back via SCAN_RESULTS_AVAILABLE_ACTION
            // but we also poll the cached list immediately — Android keeps a
            // cache of the last results and on most devices the list is
            // populated even without a fresh scan.
            wifi.startScan()
        } catch (_: Exception) {
            // startScan was deprecated for non-system apps in API 28+; we
            // ignore failures and just read the cached results.
        }
        try {
            @Suppress("DEPRECATION")
            val results = wifi.scanResults ?: emptyList()
            val unique = results
                .mapNotNull { it.SSID?.takeIf { s -> s.isNotBlank() } }
                .distinct()
                .sorted()
            scanResults = unique
            if (unique.isEmpty()) {
                scanError = "No networks visible. Make sure WiFi is on and Location permission is granted, then try again."
            }
        } catch (se: SecurityException) {
            scanError = "Location permission required to scan WiFi networks."
        } catch (e: Exception) {
            scanError = "Scan failed: ${e.message}"
        } finally {
            isScanning = false
        }
    }

    // Run an initial scan when the config first appears so the list isn't
    // empty on open. The user can refresh manually.
    LaunchedEffect(Unit) { runScan() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Wifi, null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "WiFi Trigger",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(
                    onClick = { runScan() },
                    enabled = !isScanning,
                ) {
                    Text(if (isScanning) "Scanning…" else "Refresh")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Service starts when this phone joins any of the selected networks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Build the merged list:
            //   1. Already-selected SSIDs (always shown so the user can
            //      uncheck even when out of range).
            //   2. Visible networks not already selected.
            val merged: List<Pair<String, Boolean>> = remember(ssids, scanResults) {
                val selected = ssids.toSortedSet().map { it to true }
                val visibleExtras = scanResults
                    .filter { it !in ssids }
                    .map { it to false }
                selected + visibleExtras
            }

            if (merged.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = scanError ?: "No saved or visible networks. Add one below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scanError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                merged.forEach { (ssid, isSelected) ->
                    val isCurrent = ssid == currentSsid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSsidsChanged(
                                    if (isSelected) ssids - ssid else ssids + ssid
                                )
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                onSsidsChanged(
                                    if (checked) ssids + ssid else ssids - ssid
                                )
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ssid,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (isCurrent) {
                                Text(
                                    text = "Currently connected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (isSelected && ssid !in scanResults) {
                                Text(
                                    text = "Saved (not visible right now)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (scanError != null && merged.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = scanError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Manual SSID entry — covers the case where the network isn't
            // currently broadcasting (e.g. the car is parked away from the
            // user) or scan permission isn't granted.
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Stop-on-disconnect toggle. Mirrors BT_DISCONNECT_STOP. When on,
            // the service stops as soon as the phone leaves any of the
            // selected SSIDs. Useful so the foreground service + mDNS doesn't
            // keep running all day after the user gets home.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stop on WiFi disconnect",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Stop the companion service when this phone leaves the selected network(s).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = stopOnDisconnect, onCheckedChange = onStopOnDisconnectChanged)
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add by name",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualEntry,
                    onValueChange = { manualEntry = it },
                    label = { Text("WiFi network name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilledTonalButton(
                    enabled = manualEntry.trim().isNotBlank() &&
                        manualEntry.trim() !in ssids,
                    onClick = {
                        val toAdd = manualEntry.trim()
                        if (toAdd.isNotBlank()) {
                            onSsidsChanged(ssids + toAdd)
                            manualEntry = ""
                        }
                    },
                ) {
                    Text("Add")
                }
            }
        }
    }
}

/**
 * Read the SSID of the currently-connected WiFi, if any. Strips the wrapping
 * quotes that [android.net.wifi.WifiInfo.getSSID] returns. Returns null if
 * not connected or if location permission isn't granted.
 */
private fun resolveCurrentSsid(wifi: android.net.wifi.WifiManager?): String? {
    if (wifi == null) return null
    return try {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val raw = info.ssid ?: return null
        val stripped = raw.removePrefix("\"").removeSuffix("\"")
        stripped.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (_: Exception) {
        null
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

@Composable
private fun FileLoggingSection() {
    val context = LocalContext.current
    val isServiceRunning by CompanionService.isRunning.collectAsState()
    val fileLoggingActive by CompanionService.fileLoggingActive.collectAsState()
    val fileLoggingPath by CompanionService.fileLoggingPath.collectAsState()

    Text(
        text = "File Logging",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (fileLoggingActive) OalGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (fileLoggingActive) "Logging active" else "Log to file",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = fileLoggingActive,
                    enabled = true,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            val running = isServiceRunning
                            val service = getCompanionService(context)
                            if (running && service != null) {
                                service.startFileLogging()
                            } else {
                                // Service not running — piggyback on ACTION_START so
                                // logging starts atomically once the service is up,
                                // avoiding the race between startForegroundService
                                // returning and onCreate completing.
                                val intent = android.content.Intent(context, CompanionService::class.java).apply {
                                    action = CompanionService.ACTION_START
                                    putExtra(CompanionService.EXTRA_START_LOGGING, true)
                                }
                                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                            }
                        } else {
                            getCompanionService(context)?.stopFileLogging()
                        }
                    },
                )
            }

            if (!isServiceRunning && !fileLoggingActive) {
                Text(
                    text = "Toggle on to start the service and begin logging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (fileLoggingActive && fileLoggingPath != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = fileLoggingPath!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Logs persist as long as this toggle is on, regardless of " +
                    "connection state. If no connection is ever made within 10 minutes " +
                    "of enabling, logging auto-disables to prevent runaway files.\n\n" +
                    "Recommended only for troubleshooting — turn this back off " +
                    "when you're done. Continuous file logging adds I/O overhead " +
                    "and can affect proxy throughput.\n\n" +
                    "Files: Android/data/com.openautolink.companion/files/openautolink/logs/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Get the CompanionService instance if it's running.
 * Uses [Context.getSystemService]-style lookup via the service connection.
 * Since CompanionService is a started (not bound) service, we access it
 * by sending intents. But for toggle we need the instance directly.
 * We use a simple companion-object reference set in onCreate.
 */
private fun getCompanionService(context: Context): CompanionService? {
    // We'll add a static instance ref to CompanionService
    return CompanionService.instance
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
