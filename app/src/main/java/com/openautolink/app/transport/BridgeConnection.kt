package com.openautolink.app.transport

import android.net.Network
import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge connection interface — owns 3 TCP connections to the bridge.
 * Control (5288), Video (5290), Audio (5289).
 */
interface BridgeConnection {
    val connectionState: StateFlow<ConnectionState>
    val controlMessages: Flow<ControlMessage>
    val videoFrames: Flow<VideoFrame>
    val audioFrames: Flow<AudioFrame>

    suspend fun connect(
        host: String,
        controlPort: Int = 5288,
        network: Network? = null,
        networkResolver: NetworkResolver? = null,
    )
    suspend fun disconnect()
    suspend fun sendControlMessage(message: ControlMessage)
    suspend fun connectVideo(host: String, port: Int)
    suspend fun disconnectVideo()
    suspend fun connectAudio(host: String, port: Int)
    suspend fun disconnectAudio()
    fun sendMicAudio(frame: AudioFrame)
}

fun interface NetworkResolver {
    fun resolve(): Network?
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,        // TCP to bridge established, hello exchanged
    PHONE_CONNECTED,  // Phone AA session active on bridge
    STREAMING         // Video/audio flowing
}
