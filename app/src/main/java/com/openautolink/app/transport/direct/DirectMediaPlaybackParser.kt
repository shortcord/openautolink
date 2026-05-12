package com.openautolink.app.transport.direct

import com.openautolink.app.proto.MediaPlayback
import com.openautolink.app.transport.ControlMessage
import java.util.Base64

internal object DirectMediaPlaybackParser {
    private const val MSG_PLAYBACK_STATUS = 0x8001
    private const val MSG_PLAYBACK_METADATA = 0x8003

    fun parse(msg: AaMessage): ControlMessage? {
        val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
        return when (msg.type) {
            MSG_PLAYBACK_STATUS -> parsePlaybackStatus(data)
            MSG_PLAYBACK_METADATA -> parseMetadata(data)
            else -> null
        }
    }

    private fun parsePlaybackStatus(data: ByteArray): ControlMessage.MediaPlaybackState {
        val status = MediaPlayback.MediaPlaybackStatus.parseFrom(data)
        return ControlMessage.MediaPlaybackState(
            playing = status.state == MediaPlayback.MediaPlaybackStatus.State.PLAYING,
            positionMs = status.playbackSeconds * 1000L
        )
    }

    private fun parseMetadata(data: ByteArray): ControlMessage.MediaMetadata {
        val metadata = MediaPlayback.MediaMetaData.parseFrom(data)
        return ControlMessage.MediaMetadata(
            title = metadata.song.takeIf { it.isNotEmpty() },
            artist = metadata.artist.takeIf { it.isNotEmpty() },
            album = metadata.album.takeIf { it.isNotEmpty() },
            durationMs = metadata.durationSeconds.takeIf { it > 0 }?.times(1000L),
            positionMs = null,
            playing = null,
            albumArtBase64 = metadata.albumArt.takeIf { !it.isEmpty }?.let {
                Base64.getEncoder().encodeToString(it.toByteArray())
            }
        )
    }
}
