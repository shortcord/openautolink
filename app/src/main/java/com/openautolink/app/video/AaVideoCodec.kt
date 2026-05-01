package com.openautolink.app.video

/**
 * Android Auto media codec identifiers from MediaCodecType.proto.
 */
object AaVideoCodec {
    const val H264_BP = 3
    const val VP9 = 5
    const val AV1 = 6
    const val H265 = 7

    fun preferenceToAaType(codec: String): Int = when (codec.normalizedPreference()) {
        "vp9" -> VP9
        "h264" -> H264_BP
        else -> H265
    }

    fun aaTypeToPreference(codecType: Int): String? = when (codecType) {
        H265 -> "h265"
        AV1 -> "av1"
        VP9 -> "vp9"
        H264_BP -> "h264"
        else -> null
    }

    fun String.normalizedPreference(): String = when (trim().lowercase()) {
        "h.264", "h264", "avc" -> "h264"
        "h.265", "h265", "hevc" -> "h265"
        "vp9", "vp-9" -> "vp9"
        "av1", "av-1" -> "av1"
        "auto" -> "auto"
        else -> this.trim().lowercase()
    }
}
