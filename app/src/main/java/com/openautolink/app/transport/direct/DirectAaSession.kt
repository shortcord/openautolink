package com.openautolink.app.transport.direct

import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.proto.Control
import com.openautolink.app.proto.Media
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Direct AA session â€” speaks AA wire protocol directly to the phone.
 *
 * Lifecycle:
 * 1. start() â†’ opens TCP server on port 5288, registers NSD
 * 2. Phone connects â†’ version exchange â†’ SSL handshake â†’ service discovery
 * 3. Phone opens channels â†’ video/audio/sensor streaming
 * 4. stop() â†’ closes everything
 *
 * This replaces the bridge: instead of OAL protocol over TCP to an SBC,
 * we speak the raw AA wire protocol directly to the phone.
 */
class DirectAaSession(
    private val scope: CoroutineScope,
    private val context: Context,
) {
    companion object {
        private const val TAG = "DirectAaSession"
        private const val PORT = 5288
        private const val NSD_SERVICE_TYPE = "_aawireless._tcp."
        private const val NSD_SERVICE_NAME = "OpenAutoLink"
        private const val MAX_UNACKED = 30  // Flow control window for wireless
    }

    // Public state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    val controlMessages: Flow<ControlMessage> = _controlMessages.asSharedFlow()

    private val _videoFrames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val videoFrames: Flow<VideoFrame> = _videoFrames.asSharedFlow()

    private val _audioFrames = MutableSharedFlow<AudioFrame>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioFrames: Flow<AudioFrame> = _audioFrames.asSharedFlow()

    // Internal state
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    private var readJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistered = false

    private val codec = AaWireCodec()
    private val sslEngine = AaSslEngine()
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var sslActive = false

    private var videoAssembler: AaVideoAssembler? = null
    private var unackedFrames = 0

    // Video config from settings
    var videoConfig = DirectServiceDiscovery.VideoConfig()

    /**
     * Start listening for incoming phone connections.
     */
    fun start() {
        if (serverJob?.isActive == true) return
        _connectionState.value = ConnectionState.DISCONNECTED

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
                registerNsd()
                OalLog.i(TAG, "Listening on port $PORT")
                _controlMessages.emit(ControlMessage.Hello(
                    name = "DirectMode", version = 1, capabilities = listOf("direct"),
                    videoPort = 0, audioPort = 0,
                ))

                while (true) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    val client = serverSocket?.accept() ?: break
                    OalLog.i(TAG, "Phone connected from ${client.inetAddress?.hostAddress}")
                    handleConnection(client)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (serverSocket != null && !serverSocket!!.isClosed) {
                    OalLog.e(TAG, "Server error: ${e.message}")
                }
            } finally {
                unregisterNsd()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Stop the session and close all connections.
     */
    fun stop() {
        readJob?.cancel()
        readJob = null
        serverJob?.cancel()
        serverJob = null
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
        sslEngine.release()
        sslActive = false
        unregisterNsd()
        _connectionState.value = ConnectionState.DISCONNECTED
        OalLog.i(TAG, "Stopped")
    }

    /**
     * Send an AA message to the phone.
     */
    suspend fun sendMessage(msg: AaMessage) = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext
        try {
            if (sslActive) {
                // Encrypt the full message (header + type + payload) before sending
                val raw = ByteArray(msg.wireSize)
                raw[0] = msg.channel.toByte()
                raw[1] = msg.flags.toByte()
                val length = AaMessage.TYPE_SIZE + msg.payloadLength
                raw[2] = (length shr 8).toByte()
                raw[3] = (length and 0xFF).toByte()
                raw[4] = (msg.type shr 8).toByte()
                raw[5] = (msg.type and 0xFF).toByte()
                if (msg.payloadLength > 0) {
                    System.arraycopy(msg.payload, msg.payloadOffset, raw, 6, msg.payloadLength)
                }
                val encrypted = sslEngine.encrypt(raw) ?: return@withContext
                synchronized(out) { out.write(encrypted); out.flush() }
            } else {
                synchronized(out) { codec.encode(msg, out) }
            }
        } catch (e: Exception) {
            OalLog.e(TAG, "Send error: ${e.message}")
        }
    }

    // â”€â”€ Connection handling â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun handleConnection(socket: Socket) {
        clientSocket = socket
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        sslActive = false

        try {
            _connectionState.value = ConnectionState.CONNECTING

            // 1. Version exchange
            OalLog.i(TAG, "Starting version exchange")
            codec.writeVersionRequest(outputStream!!)
            val (major, minor) = codec.readVersionResponse(inputStream!!)
            OalLog.i(TAG, "Phone version: $major.$minor")

            // 2. SSL handshake
            OalLog.i(TAG, "Starting SSL handshake")
            if (!sslEngine.performHandshake(inputStream!!, outputStream!!)) {
                OalLog.e(TAG, "SSL handshake failed")
                _controlMessages.emit(ControlMessage.Error(7, "SSL handshake failed"))
                return
            }
            sslActive = true
            OalLog.i(TAG, "SSL handshake complete")

            // 3. Send AUTH_COMPLETE
            sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.AUTH_COMPLETE,
                byteArrayOf(0x08, 0x00))) // status = OK

            // 4. Wait for ServiceDiscoveryRequest, respond with our capabilities
            _connectionState.value = ConnectionState.CONNECTED

            // 5. Start read loop
            videoAssembler = AaVideoAssembler(
                bufferSize = if (videoConfig.codec == "H.265") AaVideoAssembler.H265_BUFFER_SIZE
                             else AaVideoAssembler.DEFAULT_BUFFER_SIZE,
                onFrameCorrupted = { scope.launch { sendVideoFocusNotification() } },
            )

            readLoop()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OalLog.e(TAG, "Connection error: ${e.message}")
        } finally {
            _controlMessages.emit(ControlMessage.PhoneDisconnected("connection_closed"))
            try { socket.close() } catch (_: Exception) {}
            clientSocket = null
            inputStream = null
            outputStream = null
            sslActive = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun readLoop() {
        val input = inputStream ?: return

        while (true) {
            // Read encrypted data and decrypt
            // In SSL mode, we read raw bytes from the socket, decrypt, then parse AA messages
            val rawHeader = ByteArray(4)
            var offset = 0
            while (offset < 4) {
                val n = input.read(rawHeader, offset, 4 - offset)
                if (n < 0) {
                    OalLog.i(TAG, "Phone disconnected (EOF)")
                    return
                }
                offset += n
            }

            // Decrypt if SSL is active
            // TODO: The SSL layer wraps TLS records around the AA messages.
            // For now, read the TLS record, decrypt, then parse the AA message from the plaintext.
            // This needs refinement â€” HURev's approach reads full TLS records first.

            val channel = rawHeader[0].toInt() and 0xFF
            val flags = rawHeader[1].toInt() and 0xFF
            val length = ((rawHeader[2].toInt() and 0xFF) shl 8) or (rawHeader[3].toInt() and 0xFF)

            if (length < 2 || length > 65535) {
                OalLog.e(TAG, "Invalid message length: $length")
                return
            }

            val body = ByteArray(length)
            offset = 0
            while (offset < length) {
                val n = input.read(body, offset, length - offset)
                if (n < 0) return
                offset += n
            }

            val type = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            val msg = AaMessage(channel, flags, type, body, 2, length - 2)

            handleMessage(msg)
        }
    }

    // â”€â”€ Message dispatch â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun handleMessage(msg: AaMessage) {
        when (msg.channel) {
            AaChannel.CONTROL -> handleControl(msg)
            AaChannel.VIDEO -> handleVideo(msg)
            AaChannel.AUDIO_MEDIA, AaChannel.AUDIO_SPEECH, AaChannel.AUDIO_SYSTEM -> handleAudio(msg)
            AaChannel.NAVIGATION -> handleNavigation(msg)
            AaChannel.MEDIA_PLAYBACK -> handleMediaPlayback(msg)
            AaChannel.PHONE_STATUS -> handlePhoneStatus(msg)
            else -> {
                // Channel open requests come as control-type messages on any channel
                if (AaMsgType.isControl(msg.type)) {
                    handleChannelControl(msg)
                }
            }
        }
    }

    private suspend fun handleControl(msg: AaMessage) {
        when (msg.type) {
            AaMsgType.SERVICE_DISCOVERY_REQUEST -> {
                OalLog.i(TAG, "ServiceDiscoveryRequest received")
                val response = DirectServiceDiscovery.build(videoConfig)
                sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.SERVICE_DISCOVERY_RESPONSE, response))
                OalLog.i(TAG, "ServiceDiscoveryResponse sent (${response.servicesCount} services)")
            }

            AaMsgType.PING_REQUEST -> {
                // Respond to ping with same timestamp
                val payload = if (msg.payloadLength > 0) {
                    msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                } else ByteArray(0)
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.PING_RESPONSE, payload))
            }

            AaMsgType.BYEBYE_REQUEST -> {
                OalLog.i(TAG, "ByeByeRequest received")
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.BYEBYE_RESPONSE, ByteArray(0)))
                _controlMessages.emit(ControlMessage.PhoneDisconnected("byebye"))
            }

            AaMsgType.AUDIO_FOCUS_REQUEST -> {
                // Grant audio focus immediately
                val notification = Control.AudioFocusNotification.newBuilder()
                    .setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
                    .build()
                sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.AUDIO_FOCUS_NOTIFICATION, notification))
            }

            AaMsgType.NAV_FOCUS_REQUEST -> {
                // Grant nav focus
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.NAV_FOCUS_NOTIFICATION,
                    byteArrayOf(0x08, 0x02))) // type = 2 (focused)
            }

            AaMsgType.VOICE_SESSION_NOTIFICATION -> {
                OalLog.i(TAG, "Voice session notification")
            }
        }
    }

    private suspend fun handleChannelControl(msg: AaMessage) {
        when (msg.type) {
            AaMsgType.CHANNEL_OPEN_REQUEST -> {
                OalLog.i(TAG, "ChannelOpenRequest for ${AaChannel.name(msg.channel)}")
                // Respond with channel open success
                val response = Control.ChannelOpenResponse.newBuilder()
                    .setStatus(com.openautolink.app.proto.Common.MessageStatus.STATUS_SUCCESS)
                    .build()
                sendMessage(AaMessage.fromProto(msg.channel, AaMsgType.CHANNEL_OPEN_RESPONSE, response))

                // If video channel opened, send VideoFocusNotification
                if (msg.channel == AaChannel.VIDEO) {
                    sendVideoFocusNotification()
                    _controlMessages.emit(ControlMessage.PhoneConnected("phone", "wireless"))
                }
            }

            AaMsgType.MEDIA_SETUP -> {
                // Media setup â€” respond with config
                OalLog.i(TAG, "MediaSetup on ${AaChannel.name(msg.channel)}")
                val configResponse = byteArrayOf(0x08, 0x00) // status = OK
                sendMessage(AaMessage.raw(msg.channel, AaMsgType.MEDIA_CONFIG, configResponse))
            }

            AaMsgType.MEDIA_START -> {
                OalLog.i(TAG, "MediaStart on ${AaChannel.name(msg.channel)}")
                // Emit audio start for audio channels
                if (AaChannel.isAudio(msg.channel)) {
                    val purpose = when (msg.channel) {
                        AaChannel.AUDIO_MEDIA -> AudioPurpose.MEDIA
                        AaChannel.AUDIO_SPEECH -> AudioPurpose.NAVIGATION
                        AaChannel.AUDIO_SYSTEM -> AudioPurpose.ALERT
                        else -> AudioPurpose.MEDIA
                    }
                    val sampleRate = if (msg.channel == AaChannel.AUDIO_MEDIA) 48000 else 16000
                    val channels = if (msg.channel == AaChannel.AUDIO_MEDIA) 2 else 1
                    _controlMessages.emit(ControlMessage.AudioStart(purpose, sampleRate, channels))
                }
            }

            AaMsgType.MEDIA_STOP -> {
                OalLog.i(TAG, "MediaStop on ${AaChannel.name(msg.channel)}")
                if (AaChannel.isAudio(msg.channel)) {
                    val purpose = when (msg.channel) {
                        AaChannel.AUDIO_MEDIA -> AudioPurpose.MEDIA
                        AaChannel.AUDIO_SPEECH -> AudioPurpose.NAVIGATION
                        AaChannel.AUDIO_SYSTEM -> AudioPurpose.ALERT
                        else -> AudioPurpose.MEDIA
                    }
                    _controlMessages.emit(ControlMessage.AudioStop(purpose))
                }
            }

            AaMsgType.MIC_REQUEST -> {
                OalLog.i(TAG, "MicRequest on ${AaChannel.name(msg.channel)}")
                _controlMessages.emit(ControlMessage.MicStart(16000))
            }

            AaMsgType.VIDEO_FOCUS_REQUEST -> {
                OalLog.i(TAG, "VideoFocusRequest")
                sendVideoFocusNotification()
            }
        }
    }

    private suspend fun handleVideo(msg: AaMessage) {
        when (msg.type) {
            AaMsgType.MEDIA_DATA -> {
                val frame = videoAssembler?.process(msg)
                if (frame != null) {
                    _videoFrames.emit(frame)
                    // Send media ack
                    unackedFrames++
                    if (unackedFrames >= MAX_UNACKED / 2) {
                        val ack = Media.Ack.newBuilder()
                            .setSessionId(1)
                            .setAck(unackedFrames)
                            .build()
                        sendMessage(AaMessage.fromProto(AaChannel.VIDEO, AaMsgType.MEDIA_ACK, ack))
                        unackedFrames = 0
                    }
                }
            }

            AaMsgType.MEDIA_CODEC_CONFIG -> {
                OalLog.i(TAG, "Video codec config received (${msg.payloadLength}B)")
                // Forward codec config as a video frame with flag for decoder init
                val data = if (msg.payloadOffset == 0 && msg.payloadLength == msg.payload.size) {
                    msg.payload
                } else {
                    msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                }
                _videoFrames.emit(VideoFrame(
                    width = 0, height = 0,
                    ptsMs = 0L,
                    flags = VideoFrame.FLAG_CODEC_CONFIG,
                    data = data,
                ))
            }

            else -> handleChannelControl(msg)
        }
    }

    private suspend fun handleAudio(msg: AaMessage) {
        if (msg.type == AaMsgType.MEDIA_DATA) {
            val purpose = when (msg.channel) {
                AaChannel.AUDIO_MEDIA -> AudioPurpose.MEDIA
                AaChannel.AUDIO_SPEECH -> AudioPurpose.NAVIGATION
                AaChannel.AUDIO_SYSTEM -> AudioPurpose.ALERT
                else -> AudioPurpose.MEDIA
            }
            val pcm = if (msg.payloadOffset == 0 && msg.payloadLength == msg.payload.size) {
                msg.payload
            } else {
                msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
            }
            _audioFrames.emit(AudioFrame(
                direction = AudioFrame.DIRECTION_PLAYBACK,
                purpose = purpose,
                sampleRate = if (msg.channel == AaChannel.AUDIO_MEDIA) 48000 else 16000,
                channels = if (msg.channel == AaChannel.AUDIO_MEDIA) 2 else 1,
                data = pcm,
            ))

            // Ack audio
            val ack = Media.Ack.newBuilder().setSessionId(1).setAck(1).build()
            sendMessage(AaMessage.fromProto(msg.channel, AaMsgType.MEDIA_ACK, ack))
        } else {
            handleChannelControl(msg)
        }
    }

    private suspend fun handleNavigation(msg: AaMessage) {
        // Parse navigation turn events and forward as control messages
        // The nav data comes as MEDIA_DATA on the navigation channel
        if (msg.type == AaMsgType.MEDIA_START || msg.type == AaMsgType.MEDIA_DATA) {
            try {
                val navData = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                // TODO: Parse NavigationStatus proto and emit as ControlMessage.NavState
                OalLog.d(TAG, "Nav data received (${navData.size}B)")
            } catch (e: Exception) {
                OalLog.e(TAG, "Nav parse error: ${e.message}")
            }
        } else {
            handleChannelControl(msg)
        }
    }

    private suspend fun handleMediaPlayback(msg: AaMessage) {
        if (msg.type == AaMsgType.MEDIA_START || msg.type == AaMsgType.MEDIA_DATA) {
            try {
                val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                // TODO: Parse MediaPlaybackStatus and emit as ControlMessage.MediaMetadata
                OalLog.d(TAG, "Media playback data (${data.size}B)")
            } catch (e: Exception) {
                OalLog.e(TAG, "MediaPlayback parse error: ${e.message}")
            }
        } else {
            handleChannelControl(msg)
        }
    }

    private suspend fun handlePhoneStatus(msg: AaMessage) {
        OalLog.d(TAG, "PhoneStatus (${msg.payloadLength}B)")
        // TODO: Parse phone battery, signal, call state
    }

    private suspend fun sendVideoFocusNotification() {
        val focus = Media.VideoFocusNotification.newBuilder()
            .setMode(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE)
            .setUnsolicited(false)
            .build()
        sendMessage(AaMessage.fromProto(AaChannel.VIDEO, AaMsgType.VIDEO_FOCUS_NOTIFICATION, focus))
    }

    // â”€â”€ NSD (Network Service Discovery) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                port = PORT
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener)
            OalLog.i(TAG, "NSD registered: $NSD_SERVICE_NAME on port $PORT")
        } catch (e: Exception) {
            OalLog.e(TAG, "NSD registration failed: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        if (nsdRegistered) {
            try {
                nsdManager?.unregisterService(nsdListener)
            } catch (_: Exception) {}
            nsdRegistered = false
        }
    }

    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            nsdRegistered = true
            OalLog.i(TAG, "NSD service registered: ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            OalLog.e(TAG, "NSD registration failed: error $errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            nsdRegistered = false
        }
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }
}
