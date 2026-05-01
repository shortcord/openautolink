package com.openautolink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AaVideoCodecTest {

    @Test
    fun `AA codec ids map to preferences`() {
        assertEquals("h264", AaVideoCodec.aaTypeToPreference(3))
        assertEquals("vp9", AaVideoCodec.aaTypeToPreference(5))
        assertEquals("av1", AaVideoCodec.aaTypeToPreference(6))
        assertEquals("h265", AaVideoCodec.aaTypeToPreference(7))
        assertNull(AaVideoCodec.aaTypeToPreference(99))
    }

    @Test
    fun `preferences map to AA codec ids`() {
        assertEquals(3, AaVideoCodec.preferenceToAaType("H.264"))
        assertEquals(5, AaVideoCodec.preferenceToAaType("VP9"))
        assertEquals(7, AaVideoCodec.preferenceToAaType("H.265"))
        assertEquals(7, AaVideoCodec.preferenceToAaType("unknown"))
    }
}
