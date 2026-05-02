package com.openautolink.app.data

import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * One companion phone the user has connected to before. Persisted in the
 * "known phones" list so the chooser UI can show all of them, even when
 * they're not currently advertising on the car AP.
 *
 * No IP cache: GM-style automotive APs change subnet every reboot, so
 * stashing a "last known IP" would be misleading more often than helpful.
 * Identity is keyed on the stable [phoneId] (UUID published by the
 * companion); the actual IP is always re-discovered fresh via mDNS / sweep.
 */
data class KnownPhone(
    val phoneId: String,
    val friendlyName: String,
    val lastSeenMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("phone_id", phoneId)
        put("friendly_name", friendlyName)
        put("last_seen_ms", lastSeenMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): KnownPhone? {
            val id = obj.optString("phone_id", "").takeIf { it.isNotBlank() } ?: return null
            // Fall back to a short, recognizable suffix instead of the full UUID
            // when the user has cleared the friendly name.
            val rawName = obj.optString("friendly_name", "").takeIf { it.isNotBlank() }
            val name = rawName ?: "Phone-${id.take(4)}"
            val lastSeen = obj.optLong("last_seen_ms", 0L)
            // [last_known_ip] from older app versions is intentionally
            // ignored — see KnownPhone class doc.
            return KnownPhone(id, name, lastSeen)
        }
    }
}

/**
 * Persistent "known phones" list backed by [AppPreferences.knownPhonesJson].
 *
 * Pure helper around DataStore — no scope of its own. Callers observe the
 * derived [phones] flow and mutate via [upsert] / [remove] / [touch].
 *
 * Concurrency note: DataStore serializes writes internally, but each
 * mutator here does a read-modify-write. Concurrent calls can race; the
 * last writer wins. That's acceptable for our use (low write rate, eventual
 * consistency is fine), but means [touch] in particular is throttled to
 * avoid write amplification when called from a discovery hot loop.
 */
class KnownPhonesStore(private val preferences: AppPreferences) {

    companion object {
        private const val TAG = "KnownPhonesStore"
        /**
         * Minimum gap between writes for the same phone via [touch]. Keeps
         * the discovery → touch coupling from spamming DataStore when mDNS
         * + sweep both keep emitting the same phone.
         */
        private const val TOUCH_THROTTLE_MS = 30_000L
    }

    val phones: Flow<List<KnownPhone>> = preferences.knownPhonesJson.map { parse(it) }

    /** Insert-or-update by [KnownPhone.phoneId]. */
    suspend fun upsert(phone: KnownPhone) {
        val current = currentValue() ?: return
        val merged = current
            .filter { it.phoneId != phone.phoneId }
            .plus(phone)
            .sortedByDescending { it.lastSeenMs }
        preferences.setKnownPhonesJson(serialize(merged))
    }

    /** Remove a known phone by id. Also clears it as default if it was set. */
    suspend fun remove(phoneId: String) {
        val current = currentValue() ?: return
        val updated = current.filter { it.phoneId != phoneId }
        preferences.setKnownPhonesJson(serialize(updated))
        val currentDefault = currentDefaultId() ?: return
        if (currentDefault == phoneId) {
            preferences.setDefaultPhoneId("")
        }
    }

    /**
     * Update the entry for [phoneId] with the latest [friendlyName].
     * Throttled: skips writes when nothing meaningful has changed and the
     * last touch was within [TOUCH_THROTTLE_MS]. Critical because the
     * discovery → touch coupling fires on every StateFlow emission.
     */
    suspend fun touch(phoneId: String, friendlyName: String?) {
        if (phoneId.isBlank()) return
        val current = currentValue() ?: return
        val now = System.currentTimeMillis()
        val existing = current.firstOrNull { it.phoneId == phoneId }
        val resolvedName = friendlyName?.takeIf { it.isNotBlank() }
        if (existing != null) {
            val nameUnchanged = resolvedName == null || resolvedName == existing.friendlyName
            val recent = (now - existing.lastSeenMs) < TOUCH_THROTTLE_MS
            if (nameUnchanged && recent) return
        }
        val updated = if (existing != null) {
            current.map { e ->
                if (e.phoneId == phoneId) {
                    e.copy(
                        friendlyName = resolvedName ?: e.friendlyName,
                        lastSeenMs = now,
                    )
                } else e
            }
        } else {
            current + KnownPhone(
                phoneId = phoneId,
                friendlyName = resolvedName ?: "Phone-${phoneId.take(4)}",
                lastSeenMs = now,
            )
        }
        preferences.setKnownPhonesJson(serialize(updated))
    }

    private suspend fun currentValue(): List<KnownPhone>? {
        return try {
            parse(preferences.knownPhonesJson.first())
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to read known phones: ${e.message}")
            null
        }
    }

    private suspend fun currentDefaultId(): String? {
        return try {
            preferences.defaultPhoneId.first()
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to read default phone id: ${e.message}")
            null
        }
    }

    private fun serialize(phones: List<KnownPhone>): String {
        return try {
            val arr = JSONArray()
            phones.forEach { arr.put(it.toJson()) }
            arr.toString()
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to serialize known phones: ${e.message}")
            "[]"
        }
    }

    private fun parse(json: String): List<KnownPhone> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { KnownPhone.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            OalLog.w(TAG, "Failed to parse known phones; returning empty: ${e.message}")
            emptyList()
        }
    }
}
