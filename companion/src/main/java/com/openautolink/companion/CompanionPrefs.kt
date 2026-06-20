package com.openautolink.companion

/** SharedPreferences keys for the companion app. */
object CompanionPrefs {
    const val NAME = "OalCompanionPrefs"
    const val SECRETS_NAME = "OalCompanionSecrets"

    const val AUTO_START_MODE = "auto_start_mode"
    const val AUTO_START_BT_MACS = "auto_start_bt_macs"
    const val BT_DISCONNECT_STOP = "bt_disconnect_stop"
    const val AUTO_START_WIFI_SSIDS = "auto_start_wifi_ssids"
    const val WIFI_DISCONNECT_STOP = "wifi_disconnect_stop"

    const val AUTO_START_OFF = 0
    const val AUTO_START_BT = 1
    const val AUTO_START_WIFI = 2
    const val AUTO_START_APP_OPEN = 3
    const val AUTO_START_BT_AND_WIFI = 4

    // Car Hotspot WiFi credentials for WifiNetworkSpecifier auto-connect.
    // Stored as Set<String>, each entry formatted as "ssid\tpassword".
    const val CAR_WIFI_ENTRIES = "car_wifi_entries"

    // Connection mode — distinguishes which side hosts the WiFi network.
    // PHONE_HOTSPOT: phone is the AP, car is the client (current default).
    // CAR_HOTSPOT:   car AP is the network, phone is one of N clients.
    //                Multi-phone discovery via mDNS happens in this mode.
    const val CONNECTION_MODE = "connection_mode"
    const val MODE_PHONE_HOTSPOT = "phone_hotspot"
    const val MODE_CAR_HOTSPOT = "car_hotspot"
    const val DEFAULT_CONNECTION_MODE = MODE_CAR_HOTSPOT

    // Phone identity — published in mDNS TXT records so the car can tell
    // phones apart and remember a preferred default.
    const val PHONE_ID = "phone_id"             // stable UUID, generated on first launch
    const val PHONE_FRIENDLY_NAME = "phone_friendly_name"  // user-editable, defaults to Build.MODEL

    /**
     * Returns the persistent phone UUID, generating one on first call.
     */
    fun getOrCreatePhoneId(prefs: android.content.SharedPreferences): String {
        val existing = prefs.getString(PHONE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(PHONE_ID, fresh).apply()
        return fresh
    }

    /**
     * Returns the user-friendly phone name. Defaults to the user-set device
     * name (Settings -> About phone -> Device name, also used as the BT name)
     * on first read, falling back to Build.MODEL if unavailable.
     */
    fun getFriendlyName(prefs: android.content.SharedPreferences): String {
        val existing = prefs.getString(PHONE_FRIENDLY_NAME, null)
        if (!existing.isNullOrBlank()) return existing
        return resolveDefaultDeviceName() ?: (android.os.Build.MODEL ?: "Phone")
    }

    private fun resolveDefaultDeviceName(): String? {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return null
            val cr = app.contentResolver
            val candidates = listOf(
                android.provider.Settings.Global.DEVICE_NAME,
                "device_name",
                "bluetooth_name",
            )
            for (key in candidates) {
                val v = runCatching { android.provider.Settings.Global.getString(cr, key) }.getOrNull()
                if (!v.isNullOrBlank()) return v
                val s = runCatching { android.provider.Settings.Secure.getString(cr, key) }.getOrNull()
                if (!s.isNullOrBlank()) return s
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** Identity probe response format: `OAL!{phoneId}\t{friendlyName}\n` */
    const val IDENTITY_RESPONSE_FORMAT = "OAL!%s\t%s\n"

    /**
     * Returns the identity response bytes for the given prefs:
     * `OAL!{phoneId}\t{friendlyName}\n` encoded as UTF-8.
     */
    fun identityResponseBytes(prefs: android.content.SharedPreferences): ByteArray {
        val phoneId = getOrCreatePhoneId(prefs)
        val friendlyName = getFriendlyName(prefs)
        return String.format(IDENTITY_RESPONSE_FORMAT, phoneId, friendlyName).toByteArray(Charsets.UTF_8)
    }

    /**
     * Returns the current WiFi SSID (stripped of quotes), or null if
     * not connected or the SSID is unknown.
     */
    @android.annotation.SuppressLint("MissingPermission", "Deprecated_ReplaceQuotedSsid")
    fun currentSsid(context: android.content.Context): String? {
        val wm = context.applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return null
        val raw = wm.connectionInfo?.ssid?.replace("\"", "") ?: return null
        return if (raw.isBlank() || raw == "<unknown ssid>") null else raw
    }
}