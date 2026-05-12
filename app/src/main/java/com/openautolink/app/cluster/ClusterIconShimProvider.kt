package com.openautolink.app.cluster

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Debug-only shim ContentProvider that claims the orphaned Templates Host authority for cluster icons.
 *
 * GM's Templates Host has a ClusterIconContentProvider class but never registers it in its
 * manifest. When Templates Host converts CarIcon maneuver icons into navstate2 protobuf,
 * it calls contentResolver.insert() against this authority. The first failure sets
 * `skipIcons = true` permanently, disabling all icon delivery for the session.
 *
 * This shim must not be registered in release builds because Play requires provider authorities
 * to be globally unique across developers, and this authority belongs to Google.
 *
 * This shim implements the 3-method contract Templates Host expects:
 * - insert(): cache PNG bytes keyed by iconId
 * - query(): return contentUri + aspectRatio metadata
 * - openFile(): serve cached PNG via pipe
 */
class ClusterIconShimProvider : ContentProvider() {

    companion object {
        private const val TAG = "ClusterIconShim"
        private const val AUTHORITY =
            "com.google.android.apps.automotive.templates.host.ClusterIconContentProvider"
        private const val MAX_CACHE_SIZE = 20
    }

    private val iconCache: MutableMap<String, ByteArray> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ByteArray>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
                    size > MAX_CACHE_SIZE
            }
        )

    override fun onCreate(): Boolean {
        Log.i(TAG, "ClusterIconShimProvider registered")
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) {
            Log.w(TAG, "insert() called with null ContentValues — uri=$uri")
            return "content://$AUTHORITY/img/empty".toUri()
        }

        val iconId = values.getAsString("iconId")
        val data = values.getAsByteArray("data")

        if (iconId == null || data == null) {
            Log.w(TAG, "insert() missing iconId or data — iconId=$iconId dataSize=${data?.size}")
            return "content://$AUTHORITY/img/unknown".toUri()
        }

        val cacheKey = "cluster_icon_$iconId"
        val isNew = !iconCache.containsKey(cacheKey)
        iconCache[cacheKey] = data
        val resultUri = "content://$AUTHORITY/img/$cacheKey".toUri()
        Log.i(TAG, "insert() ${if (isNew) "NEW" else "UPDATE"} iconId=$iconId " +
            "(${data.size} bytes, cache=${iconCache.size}/$MAX_CACHE_SIZE) → $resultUri")
        return resultUri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("contentUri", "aspectRatio"))
        if (selection == null) {
            Log.d(TAG, "query() null selection — returning empty cursor")
            return cursor
        }

        val cacheKey = "cluster_icon_$selection"
        val data = iconCache[cacheKey]
        if (data != null) {
            val contentUri = "content://$AUTHORITY/img/$cacheKey"
            cursor.addRow(arrayOf<Any>(contentUri, 1.0f))
            Log.i(TAG, "query() HIT iconId=$selection (${data.size} bytes) → $contentUri")
        } else {
            Log.w(TAG, "query() MISS iconId=$selection (not in cache, cache=${iconCache.size})")
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val cacheKey = uri.lastPathSegment ?: run {
            Log.w(TAG, "openFile() null lastPathSegment — uri=$uri")
            return null
        }
        val data = iconCache[cacheKey] ?: run {
            Log.w(TAG, "openFile() MISS cacheKey=$cacheKey (not in cache, cache=${iconCache.size})")
            return null
        }

        Log.i(TAG, "openFile() SERVING cacheKey=$cacheKey (${data.size} bytes) — " +
            "CLUSTER IS READING OUR ICON")

        val pipe = ParcelFileDescriptor.createPipe()
        val writeEnd = pipe[1]

        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { stream ->
                stream.write(data)
            }
        }.start()

        return pipe[0]
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "image/png"
}
