package com.openautolink.app.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * Selects the best hardware video decoder for a given codec type.
 * Prefers Codec2 (c2.*) decoders over OMX.
 */
object CodecSelector {

    private const val TAG = "CodecSelector"

    const val MIME_H264 = "video/avc"
    const val MIME_H265 = "video/hevc"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"
    const val MIME_AV1 = "video/av01"

    /**
     * Maps a codec preference string (from settings) to a MIME type.
     */
    fun codecToMime(codec: String): String = when (with(AaVideoCodec) { codec.normalizedPreference() }) {
        "h264" -> MIME_H264
        "h265" -> MIME_H265
        "vp9" -> MIME_VP9
        "av1" -> MIME_AV1
        else -> MIME_H264
    }

    /**
     * Returns true if the codec preference string represents an H.264/H.265 NAL-based codec.
     */
    fun isNalCodec(codec: String): Boolean = when (with(AaVideoCodec) { codec.normalizedPreference() }) {
        "h264", "h265" -> true
        else -> false
    }

    /**
     * Find the best hardware decoder for the given MIME type.
     * Returns the codec name or null if no HW decoder is available.
     * Prefers C2 decoders over OMX.
     */
    fun findHwDecoder(mimeType: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = mutableListOf<MediaCodecInfo>()

        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            if (!info.isHardwareAccelerated) continue
            val types = info.supportedTypes
            if (types.any { it.equals(mimeType, ignoreCase = true) }) {
                candidates.add(info)
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No HW decoder for $mimeType")
            return null
        }

        // Prefer C2 decoders over OMX
        val c2 = candidates.firstOrNull { it.name.startsWith("c2.") }
        val selected = c2 ?: candidates.first()
        Log.i(TAG, "Selected decoder: ${selected.name} for $mimeType")
        return selected.name
    }

    /**
     * Find any decoder (HW preferred, SW fallback) for the given MIME type.
     * Returns the codec name.
     */
    fun findDecoder(mimeType: String): String? {
        // Try HW first
        findHwDecoder(mimeType)?.let { return it }

        // Fall back to any decoder
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes
            if (types.any { it.equals(mimeType, ignoreCase = true) }) {
                Log.i(TAG, "Falling back to SW decoder: ${info.name} for $mimeType")
                return info.name
            }
        }

        Log.e(TAG, "No decoder at all for $mimeType")
        return null
    }

    /**
     * List all available decoder names for a MIME type (for diagnostics).
     */
    fun listDecoders(mimeType: String): List<String> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos
            .filter { !it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .map { "${it.name}${if (it.isHardwareAccelerated) " [HW]" else " [SW]"}" }
    }
}
