package com.openautolink.app.transport.direct

import com.openautolink.app.proto.Media
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectServiceDiscoveryTest {

    @Test
    fun `auto video config advertises h265 vp9 and h264 fallbacks`() {
        val config = DirectServiceDiscovery.VideoConfig(codec = "auto", width = 3840, height = 2160)
        val response = DirectServiceDiscovery.build(video = config)
        val video = response.servicesList.first { it.id == AaChannel.VIDEO }.mediaSinkService

        assertEquals(11, video.videoConfigsCount)
        assertEquals(11, DirectServiceDiscovery.videoConfigCount(config))
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265, video.availableType)
        assertEquals(5, video.videoConfigsList.count { it.videoCodecType == Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265 })
        assertEquals(3, video.videoConfigsList.count { it.videoCodecType == Media.MediaCodecType.MEDIA_CODEC_VIDEO_VP9 })
        assertEquals(3, video.videoConfigsList.count { it.videoCodecType == Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP })
    }

    @Test
    fun `manual vp9 config accepts lower-case preference`() {
        val config = DirectServiceDiscovery.VideoConfig(codec = "vp9", width = 2560, height = 1440)
        val response = DirectServiceDiscovery.build(video = config)
        val video = response.servicesList.first { it.id == AaChannel.VIDEO }.mediaSinkService

        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_VP9, video.availableType)
        assertEquals(6, video.videoConfigsCount)
        assertEquals(6, DirectServiceDiscovery.videoConfigCount(config))
    }
}
