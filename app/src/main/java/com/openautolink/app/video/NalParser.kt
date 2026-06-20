package com.openautolink.app.video

import java.io.ByteArrayOutputStream

/**
 * NAL unit parser for H.264 and H.265 bitstreams.
 * Identifies NAL types from raw codec data to detect SPS/PPS/VPS and IDR frames.
 */
object NalParser {

    // H.264 NAL types
    const val H264_NAL_SLICE = 1
    const val H264_NAL_IDR = 5
    const val H264_NAL_SEI = 6
    const val H264_NAL_SPS = 7
    const val H264_NAL_PPS = 8

    // H.265 NAL types
    const val H265_NAL_IDR_W_RADL = 19
    const val H265_NAL_IDR_N_LP = 20
    const val H265_NAL_VPS = 32
    const val H265_NAL_SPS = 33
    const val H265_NAL_PPS = 34

    /**
     * Extract the H.264 NAL type from the first byte after a start code.
     * NAL type = byte & 0x1F
     */
    fun h264NalType(nalByte: Byte): Int = nalByte.toInt() and 0x1F

    /**
     * Extract the H.265 NAL type from the first byte after a start code.
     * NAL type = (byte >> 1) & 0x3F
     */
    fun h265NalType(nalByte: Byte): Int = (nalByte.toInt() shr 1) and 0x3F

    /**
     * Find the first NAL unit start code and return the NAL type byte (raw).
     * Start codes: 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01
     * Returns -1 if no start code found.
     */
    private fun findFirstNalTypeByte(data: ByteArray, offset: Int = 0): Int {
        val pos = findStartCode(data, offset)
        if (pos < 0) return -1
        val nalBytePos = pos + startCodeLength(data, pos)
        if (nalBytePos >= data.size) return -1
        return data[nalBytePos].toInt() and 0xFF
    }

    fun findFirstH264NalType(data: ByteArray, offset: Int = 0): Int {
        val raw = findFirstNalTypeByte(data, offset)
        return if (raw < 0) -1 else raw and 0x1F
    }

    fun findFirstH265NalType(data: ByteArray, offset: Int = 0): Int {
        val raw = findFirstNalTypeByte(data, offset)
        return if (raw < 0) -1 else (raw shr 1) and 0x3F
    }

    /**
     * Check if H.264 data contains codec config (SPS or PPS).
     */
    fun isH264CodecConfig(data: ByteArray): Boolean {
        val raw = findFirstNalTypeByte(data)
        if (raw < 0) return false
        val h264Type = raw and 0x1F
        return h264Type == H264_NAL_SPS || h264Type == H264_NAL_PPS
    }

    /**
     * Check if H.264 data is an IDR (keyframe).
     */
    fun isH264Idr(data: ByteArray): Boolean {
        val raw = findFirstNalTypeByte(data)
        return raw >= 0 && (raw and 0x1F) == H264_NAL_IDR
    }

    /**
     * Check if H.265 data contains codec config (VPS, SPS, or PPS).
     */
    fun isH265CodecConfig(data: ByteArray): Boolean {
        val raw = findFirstNalTypeByte(data)
        if (raw < 0) return false
        val h265Type = (raw shr 1) and 0x3F
        return h265Type == H265_NAL_VPS || h265Type == H265_NAL_SPS || h265Type == H265_NAL_PPS
    }

    /**
     * Check if H.265 data is an IDR (keyframe).
     */
    fun isH265Idr(data: ByteArray): Boolean {
        val raw = findFirstNalTypeByte(data)
        if (raw < 0) return false
        val h265Type = (raw shr 1) and 0x3F
        return h265Type == H265_NAL_IDR_W_RADL || h265Type == H265_NAL_IDR_N_LP
    }

    /**
     * Collect all NAL types present in the data (H.264).
     * Useful for determining if a buffer contains both SPS and PPS.
     */
    fun collectH264NalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        forEachNalUnit(data) { nalBytePos -> types.add(h264NalType(data[nalBytePos])) }
        return types
    }

    /**
     * Collect all NAL types present in the data (H.265).
     */
    fun collectH265NalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        forEachNalUnit(data) { nalBytePos -> types.add(h265NalType(data[nalBytePos])) }
        return types
    }

    /**
     * Check if data contains an IDR NAL unit (H.264 type 5, or H.265 types 19/20).
     * Scans all NAL units in the buffer — handles combined SPS+PPS+IDR frames.
     *
     * Optimized: checks the raw NAL byte directly rather than calling separate
     * H.264 and H.265 type extractors on every NAL unit.
     */
    fun containsIdr(data: ByteArray): Boolean {
        var found = false
        forEachNalUnit(data) { nalBytePos ->
            val raw = data[nalBytePos].toInt() and 0xFF
            // H.264 IDR = type 5 → raw byte & 0x1F == 5
            // H.265 IDR_W_RADL = type 19 → (raw >> 1) & 0x3F == 19
            // H.265 IDR_N_LP = type 20 → (raw >> 1) & 0x3F == 20
            if ((raw and 0x1F) == H264_NAL_IDR || ((raw shr 1) and 0x3F) == H265_NAL_IDR_W_RADL || ((raw shr 1) and 0x3F) == H265_NAL_IDR_N_LP) {
                found = true; return@forEachNalUnit
            }
        }
        return found
    }

    /**
     * Iterate over each NAL unit in the data, calling [action] with the
     * position of the NAL type byte (immediately after the start code).
     * Stops early if [action] calls `return@forEachNalUnit`.
     */
    inline fun forEachNalUnit(data: ByteArray, action: (nalBytePos: Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val pos = findStartCode(data, offset)
            if (pos < 0) break
            val nalBytePos = pos + startCodeLength(data, pos)
            if (nalBytePos >= data.size) break
            action(nalBytePos)
            offset = nalBytePos + 1
        }
    }

    /**
     * Find the position of the next start code (0x000001 or 0x00000001) starting from offset.
     * Returns -1 if not found.
     *
     * Optimization: when a non-zero byte is found, skip past it immediately since
     * start codes must begin with 0x00. When a single 0x00 is followed by non-zero,
     * skip both bytes (two consecutive zeros are required for any start code).
     */
    fun findStartCode(data: ByteArray, offset: Int = 0): Int {
        if (data.size < offset + 3) return -1
        var i = offset
        val scanLimit = data.size - 3
        while (i < scanLimit) {
            if (data[i] == 0.toByte()) {
                if (data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) return i
                    if (i < data.size - 3 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        return i
                    }
                }
                // data[i]==0 but data[i+1]!=0 — skip past the zero
                i += 2
            } else {
                i++
            }
        }
        return -1
    }

    /**
     * Returns the length of the start code at the given position (3 or 4 bytes).
     */
    fun startCodeLength(data: ByteArray, pos: Int): Int {
        if (pos + 3 < data.size &&
            data[pos] == 0.toByte() && data[pos + 1] == 0.toByte() &&
            data[pos + 2] == 0.toByte() && data[pos + 3] == 1.toByte()
        ) return 4
        return 3
    }

    /**
     * Parse H.264 SPS NAL unit to extract video resolution (width x height).
     * The data may contain multiple NALs (SPS + PPS). Finds the SPS and reads
     * pic_width_in_mbs_minus1 and pic_height_in_map_units_minus1 fields.
     *
     * Returns Pair(width, height) or null if SPS cannot be parsed.
     */
    fun parseSpsResolution(data: ByteArray): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null
        forEachNalUnit(data) { nalBytePos ->
            if (h264NalType(data[nalBytePos]) == H264_NAL_SPS) {
                result = parseSpsNal(data, nalBytePos)
                return@forEachNalUnit
            }
        }
        return result
    }

    private fun parseSpsNal(data: ByteArray, nalStart: Int): Pair<Int, Int>? {
        // Minimal SPS parsing using a bit reader
        // SPS layout: nal_unit_type(8) profile_idc(8) constraint_flags(8) level_idc(8) seq_parameter_set_id(ue)
        // Then depending on profile: chroma_format_idc(ue) etc.
        // Then: log2_max_frame_num(ue), pic_order_cnt_type(ue), ...
        // Then: max_num_ref_frames(ue), gaps_in_frame_num_value_allowed_flag(1)
        // Finally: pic_width_in_mbs_minus1(ue), pic_height_in_map_units_minus1(ue)
        try {
            val reader = BitReader(data, nalStart + 1) // skip NAL byte
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint_set_flags
            reader.readBits(8) // level_idc
            reader.readUe() // seq_parameter_set_id

            // High profiles have extra fields
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) reader.readBits(1) // separate_colour_plane_flag
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1)
                if (seqScalingMatrixPresent == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (reader.readBits(1) == 1) { // scaling_list_present_flag
                            skipScalingList(reader, if (i < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            if (picOrderCntType == 0) {
                reader.readUe() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBits(1) // delta_pic_order_always_zero_flag
                reader.readSe() // offset_for_non_ref_pic
                reader.readSe() // offset_for_top_to_bottom_field
                val numRefFrames = reader.readUe()
                for (i in 0 until numRefFrames) reader.readSe()
            }

            reader.readUe() // max_num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()

            val width = (picWidthInMbsMinus1 + 1) * 16
            val height = (picHeightInMapUnitsMinus1 + 1) * 16

            return Pair(width, height)
        } catch (e: Exception) {
            return null
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = reader.readSe()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    /** Simple Exp-Golomb / bit reader for SPS parsing. */
    private class BitReader(private val data: ByteArray, private var bytePos: Int) {
        private var bitPos: Int = 0

        fun readBits(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                if (bytePos >= data.size) throw IndexOutOfBoundsException()
                value = (value shl 1) or ((data[bytePos].toInt() shr (7 - bitPos)) and 1)
                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
            return value
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (readBits(1) == 0) {
                leadingZeros++
                if (leadingZeros > 31) throw IllegalStateException("Invalid exp-golomb")
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        fun readSe(): Int {
            val ue = readUe()
            return if (ue % 2 == 0) -(ue / 2) else (ue + 1) / 2
        }

        fun skipBits(n: Int) {
            for (i in 0 until n) {
                if (bytePos >= data.size) throw IndexOutOfBoundsException()
                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
        }
    }

    /**
     * Parse H.265 SPS NAL unit to extract video resolution.
     * Finds the SPS NAL in the data and reads pic_width_in_luma_samples
     * and pic_height_in_luma_samples.
     *
     * Returns Pair(width, height) or null if SPS cannot be parsed.
     */
    fun parseH265SpsResolution(data: ByteArray): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null
        forEachNalUnit(data) { nalBytePos ->
            if (nalBytePos + 1 < data.size && h265NalType(data[nalBytePos]) == H265_NAL_SPS) {
                result = parseH265SpsNal(data, nalBytePos)
                return@forEachNalUnit
            }
        }
        return result
    }

    private fun parseH265SpsNal(data: ByteArray, nalStart: Int): Pair<Int, Int>? {
        try {
            // Convert NAL to RBSP by removing emulation prevention bytes (0x00 0x00 0x03)
            val rbsp = removeEmulationPreventionBytes(data, nalStart)
            // H.265 NAL header is 2 bytes (not 1 like H.264)
            val reader = BitReader(rbsp, 2)

            // sps_video_parameter_set_id: u(4)
            reader.readBits(4)
            // sps_max_sub_layers_minus1: u(3)
            val maxSubLayers = reader.readBits(3)
            // sps_temporal_id_nesting_flag: u(1)
            reader.readBits(1)

            // profile_tier_level(1, maxSubLayers)
            skipProfileTierLevel(reader, maxSubLayers)

            // sps_seq_parameter_set_id: ue(v)
            reader.readUe()
            // chroma_format_idc: ue(v)
            val chromaFormatIdc = reader.readUe()
            if (chromaFormatIdc == 3) {
                // separate_colour_plane_flag: u(1)
                reader.readBits(1)
            }

            // pic_width_in_luma_samples: ue(v)
            val width = reader.readUe()
            // pic_height_in_luma_samples: ue(v)
            val height = reader.readUe()

            return if (width > 0 && height > 0) Pair(width, height) else null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Remove emulation prevention bytes (0x00 0x00 0x03 → 0x00 0x00) from NAL data.
     * Required before RBSP parsing — the bitstream inserts 0x03 bytes to prevent
     * false start code detection, and these must be stripped for correct parsing.
     */
    private fun removeEmulationPreventionBytes(data: ByteArray, nalStart: Int): ByteArray {
        val result = ByteArrayOutputStream(data.size - nalStart)
        var i = nalStart
        while (i < data.size) {
            if (i + 2 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 3.toByte()
            ) {
                result.write(0)
                result.write(0)
                i += 3 // skip the 0x03 prevention byte
            } else {
                result.write(data[i].toInt() and 0xFF)
                i++
            }
        }
        return result.toByteArray()
    }

    /**
     * Skip profile_tier_level() in the H.265 SPS bitstream.
     * This structure has a fixed-size part plus variable sub_layer data.
     */
    private fun skipProfileTierLevel(reader: BitReader, maxSubLayers: Int) {
        // general_profile_space(2) + general_tier_flag(1) + general_profile_idc(5)
        reader.readBits(8)
        // general_profile_compatibility_flag[32]
        reader.skipBits(32)
        // general_progressive_source_flag(1) + interlaced(1) + non_packed(1) + frame_only(1)
        // + general_constraint_flags(44)
        reader.skipBits(48)
        // general_level_idc(8)
        reader.readBits(8)

        // sub_layer_profile_present_flag[i] and sub_layer_level_present_flag[i]
        val subLayerProfilePresent = BooleanArray(maxSubLayers)
        val subLayerLevelPresent = BooleanArray(maxSubLayers)
        for (i in 0 until maxSubLayers) {
            subLayerProfilePresent[i] = reader.readBits(1) == 1
            subLayerLevelPresent[i] = reader.readBits(1) == 1
        }
        // Align to byte boundary if maxSubLayers < 8
        if (maxSubLayers in 1..7) {
            reader.skipBits((8 - maxSubLayers) * 2)
        }
        // Sub-layer profile/level data
        for (i in 0 until maxSubLayers) {
            if (subLayerProfilePresent[i]) {
                // sub_layer_profile_space(2) + tier_flag(1) + profile_idc(5)
                reader.skipBits(8)
                // sub_layer_profile_compatibility_flag[32]
                reader.skipBits(32)
                // sub_layer constraint flags (48 bits)
                reader.skipBits(48)
            }
            if (subLayerLevelPresent[i]) {
                reader.skipBits(8) // sub_layer_level_idc
            }
        }
    }
}
