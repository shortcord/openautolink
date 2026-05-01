package com.openautolink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecSelectorTest {

    @Test
    fun `codecToMime maps h264 correctly`() {
        assertEquals(CodecSelector.MIME_H264, CodecSelector.codecToMime("h264"))
        assertEquals(CodecSelector.MIME_H264, CodecSelector.codecToMime("H264"))
        assertEquals(CodecSelector.MIME_H264, CodecSelector.codecToMime("avc"))
        assertEquals(CodecSelector.MIME_H264, CodecSelector.codecToMime("H.264"))
    }

    @Test
    fun `codecToMime maps h265 correctly`() {
        assertEquals(CodecSelector.MIME_H265, CodecSelector.codecToMime("h265"))
        assertEquals(CodecSelector.MIME_H265, CodecSelector.codecToMime("H265"))
        assertEquals(CodecSelector.MIME_H265, CodecSelector.codecToMime("hevc"))
        assertEquals(CodecSelector.MIME_H265, CodecSelector.codecToMime("H.265"))
    }

    @Test
    fun `codecToMime maps vp9 correctly`() {
        assertEquals(CodecSelector.MIME_VP9, CodecSelector.codecToMime("vp9"))
        assertEquals(CodecSelector.MIME_VP9, CodecSelector.codecToMime("VP9"))
    }

    @Test
    fun `codecToMime maps av1 correctly`() {
        assertEquals(CodecSelector.MIME_AV1, CodecSelector.codecToMime("av1"))
    }

    @Test
    fun `codecToMime defaults to h264 for unknown`() {
        assertEquals(CodecSelector.MIME_H264, CodecSelector.codecToMime("unknown"))
        assertEquals(CodecSelector.MIME_H264, CodecSelector.codecToMime(""))
    }

    @Test
    fun `isNalCodec returns true for h264 and h265`() {
        assertTrue(CodecSelector.isNalCodec("h264"))
        assertTrue(CodecSelector.isNalCodec("H264"))
        assertTrue(CodecSelector.isNalCodec("H.264"))
        assertTrue(CodecSelector.isNalCodec("avc"))
        assertTrue(CodecSelector.isNalCodec("h265"))
        assertTrue(CodecSelector.isNalCodec("H.265"))
        assertTrue(CodecSelector.isNalCodec("hevc"))
    }

    @Test
    fun `isNalCodec returns false for VP9 and AV1`() {
        assertFalse(CodecSelector.isNalCodec("vp9"))
        assertFalse(CodecSelector.isNalCodec("av1"))
        assertFalse(CodecSelector.isNalCodec("unknown"))
    }

    @Test
    fun `MIME constants are correct`() {
        assertEquals("video/avc", CodecSelector.MIME_H264)
        assertEquals("video/hevc", CodecSelector.MIME_H265)
        assertEquals("video/x-vnd.on2.vp9", CodecSelector.MIME_VP9)
        assertEquals("video/av01", CodecSelector.MIME_AV1)
    }
}
