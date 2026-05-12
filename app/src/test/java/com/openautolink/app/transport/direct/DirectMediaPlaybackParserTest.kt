package com.openautolink.app.transport.direct

import com.google.protobuf.ByteString
import com.openautolink.app.proto.MediaPlayback
import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class DirectMediaPlaybackParserTest {

    @Test
    fun `metadata messages emit current track metadata`() {
        val metadata = MediaPlayback.MediaMetaData.newBuilder()
            .setSong("Second Song")
            .setArtist("New Artist")
            .setAlbum("New Album")
            .setDurationSeconds(245)
            .setAlbumArt(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()

        val message = DirectMediaPlaybackParser.parse(
            AaMessage.fromProto(AaChannel.MEDIA_PLAYBACK, 0x8003, metadata)
        )

        val media = message as ControlMessage.MediaMetadata
        assertEquals("Second Song", media.title)
        assertEquals("New Artist", media.artist)
        assertEquals("New Album", media.album)
        assertEquals(245_000L, media.durationMs)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), media.albumArtBase64)
        assertNull(media.playing)
    }

    @Test
    fun `playback status messages do not parse as metadata`() {
        val status = MediaPlayback.MediaPlaybackStatus.newBuilder()
            .setState(MediaPlayback.MediaPlaybackStatus.State.PLAYING)
            .setPlaybackSeconds(12)
            .build()

        val message = DirectMediaPlaybackParser.parse(
            AaMessage.fromProto(AaChannel.MEDIA_PLAYBACK, 0x8001, status)
        )

        val playback = message as ControlMessage.MediaPlaybackState
        assertEquals(true, playback.playing)
        assertEquals(12_000L, playback.positionMs)
    }

    @Test
    fun `unknown media playback messages are left for channel control`() {
        val message = DirectMediaPlaybackParser.parse(
            AaMessage.raw(AaChannel.MEDIA_PLAYBACK, AaMsgType.CHANNEL_OPEN_REQUEST)
        )

        assertNull(message)
    }
}
