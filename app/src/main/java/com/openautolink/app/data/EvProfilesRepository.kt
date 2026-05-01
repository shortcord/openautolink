package com.openautolink.app.data

import android.content.Context
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Per-vehicle EV profile (driving Wh/km, max DCFC kW). Generated offline by
 * `scripts/build-ev-profiles.py` and bundled in
 * `app/src/main/assets/ev_profiles.json`. Phase 2b adds an opt-in HTTP
 * refresh that writes `ev_profiles_overlay.json` into `filesDir`; the overlay
 * shadows the bundled JSON when present.
 *
 * See docs/ev-energy-model-tuning-plan.md.
 */
data class EvProfile(
    val key: String,
    val displayName: String?,
    val drivingWhPerKm: Int?,
    val maxChargeKw: Int?,
)

class EvProfilesRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EvProfilesRepo"
        private const val BUNDLED_ASSET = "ev_profiles.json"
        private const val OVERLAY_FILE = "ev_profiles_overlay.json"
        private const val FETCH_TIMEOUT_MS = 4_000  // hard cap; doc says "no spinner > 2 s"
        private const val MAX_BYTES = 256 * 1024    // 256 KB sanity cap

        @Volatile private var instance: EvProfilesRepository? = null

        fun getInstance(context: Context): EvProfilesRepository =
            instance ?: synchronized(this) {
                instance ?: EvProfilesRepository(context.applicationContext).also { instance = it }
            }
    }

    private val gate = Any()

    @Volatile private var profiles: Map<String, EvProfile> = emptyMap()
    @Volatile private var loaded = false

    /** Load (lazy, idempotent). Cheap on subsequent calls. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(gate) {
            if (loaded) return
            val merged = HashMap<String, EvProfile>()
            // Bundled first.
            try {
                context.assets.open(BUNDLED_ASSET).use { it.bufferedReader().readText() }
                    .let { parseInto(merged, it, "bundled") }
            } catch (e: Exception) {
                OalLog.w(TAG, "bundled $BUNDLED_ASSET load failed: ${e.message}")
            }
            // Overlay overrides bundled.
            val overlay = File(context.filesDir, OVERLAY_FILE)
            if (overlay.isFile) {
                try {
                    parseInto(merged, overlay.readText(), "overlay")
                } catch (e: Exception) {
                    OalLog.w(TAG, "overlay load failed: ${e.message}")
                }
            }
            profiles = merged
            loaded = true
            DiagnosticLog.i("ev_profiles", "loaded ${profiles.size} profiles")
        }
    }

    private fun parseInto(out: MutableMap<String, EvProfile>, json: String, source: String) {
        val root = JSONObject(json)
        val profilesNode = root.optJSONObject("profiles") ?: return
        val keys = profilesNode.keys()
        var n = 0
        while (keys.hasNext()) {
            val k = keys.next()
            val obj = profilesNode.optJSONObject(k) ?: continue
            out[k.lowercase()] = EvProfile(
                key = k,
                displayName = obj.optString("displayName").ifBlank { null },
                drivingWhPerKm = if (obj.has("drivingWhPerKm")) obj.optInt("drivingWhPerKm") else null,
                maxChargeKw = if (obj.has("maxChargeKw")) obj.optInt("maxChargeKw") else null,
            )
            n++
        }
        OalLog.i(TAG, "$source: $n profiles")
    }

    /**
     * Look up a profile by VHAL identity strings. Returns null when no match.
     * Tries `Make|Model|Year`, then `Make|Model` (any year), then null.
     */
    fun lookup(make: String?, model: String?, year: String?): EvProfile? {
        ensureLoaded()
        if (make.isNullOrBlank() || model.isNullOrBlank()) return null
        val m = profiles
        val keyExact = "$make|$model|$year".lowercase()
        m[keyExact]?.let { return it }
        // Fallback: any year for this make+model.
        val prefix = "$make|$model|".lowercase()
        return m.values.firstOrNull { it.key.lowercase().startsWith(prefix) }
    }

    /** Phase 2b: download a fresh JSON from `url` and persist as overlay.
     *  Returns the new profile count, or null on failure. */
    suspend fun refreshFromNetwork(url: String): Int? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                OalLog.w(TAG, "refresh: HTTP ${conn.responseCode}")
                return@withContext null
            }
            val body = conn.inputStream.use { input ->
                input.readNBytesSafe(MAX_BYTES)
            } ?: return@withContext null
            // Validate before persisting.
            val test = HashMap<String, EvProfile>()
            parseInto(test, body, "remote")
            if (test.isEmpty()) {
                OalLog.w(TAG, "refresh: payload had 0 profiles, ignoring")
                return@withContext null
            }
            File(context.filesDir, OVERLAY_FILE).writeText(body)
            // Force re-load so subsequent lookups see the new data.
            synchronized(gate) { loaded = false }
            ensureLoaded()
            val tookMs = System.currentTimeMillis() - started
            DiagnosticLog.i("ev_profiles",
                "refreshed: ${profiles.size} profiles from ${url.take(60)}… in ${tookMs}ms")
            profiles.size
        } catch (e: Exception) {
            OalLog.w(TAG, "refresh failed: ${e.message}")
            null
        }
    }

    private fun java.io.InputStream.readNBytesSafe(max: Int): String? {
        val buf = ByteArray(max + 1)
        var total = 0
        while (true) {
            val n = read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
            if (total > max) return null  // exceed cap
        }
        return String(buf, 0, total, Charsets.UTF_8)
    }
}
