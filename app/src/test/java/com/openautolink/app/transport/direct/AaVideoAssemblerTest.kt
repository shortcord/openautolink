package com.openautolink.app.transport.direct

import com.openautolink.app.video.VideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AaVideoAssemblerTest {

    @Test
    fun `vp9 single frame strips timestamp prefix and marks keyframe`() {
        val assembler = AaVideoAssembler(codec = "vp9", onFrameCorrupted = {})
        val payload = ByteArray(8) + byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42)
        val frame = assembler.process(AaMessage(AaChannel.VIDEO, 0x0b, AaMsgType.MEDIA_DATA, payload))

        assertNotNull(frame)
        assertTrue(frame!!.isKeyframe)
        assertEquals(4, frame.data.size)
        assertEquals(0x82.toByte(), frame.data[0])
    }

    @Test
    fun `auto single frame falls back to vp9 when no nal start code exists`() {
        val assembler = AaVideoAssembler(codec = "auto", onFrameCorrupted = {})
        val payload = ByteArray(8) + byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42)
        val frame = assembler.process(AaMessage(AaChannel.VIDEO, 0x0b, AaMsgType.MEDIA_DATA, payload))

        assertNotNull(frame)
        assertTrue(frame!!.flags and VideoFrame.FLAG_KEYFRAME != 0)
        assertEquals(0x82.toByte(), frame.data[0])
    }
}
