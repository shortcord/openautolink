package com.openautolink.app.transport.direct

import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.proto.Control
import com.openautolink.app.proto.Media
import com.openautolink.app.proto.Sensors
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
        extraBufferCapacity = 30,
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
    // Per-channel session IDs from MediaStart — used in MediaAck responses.
    // The phone assigns a session ID per channel; we must echo it back in acks.
    private val channelSessionIds = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    // BT handshake for automatic phone connection
    private val btHandshake = AaBtHandshakeManager(scope)

    // Nearby Connections for peer-to-peer transport (no WiFi needed)
    private var _nearbyManager: AaNearbyManager? = null
    val nearbyManager: AaNearbyManager? get() = _nearbyManager

    // WiFi Direct for native mode
    private var wifiDirectManager: AaWifiDirectManager? = null

    // Video config from settings
    var videoConfig = DirectServiceDiscovery.VideoConfig()

    // Vehicle identity from VHAL — set before start()
    var vehicleIdentity = DirectServiceDiscovery.VehicleIdentity()

    // AA UI hide flags — set before start()
    var hideClock = true
    var hideSignal = true
    var hideBattery = true

    // Audio codec — true if we announced AAC-LC (phone will send AAC frames)
    var useAacAudio = false

    // Bluetooth MAC — set before start() for BT service announcement
    var btMacAddress = ""
    // Multi-phone: default phone name and callback
    var defaultPhoneName = ""
    var onPhoneConnected: ((name: String) -> Unit)? = null
    /** Hotspot credentials for BT handshake. Set from settings before start(). */
    var hotspotSsid: String
        get() = btHandshake.hotspotSsid
        set(value) { btHandshake.hotspotSsid = value }

    var hotspotPassword: String
        get() = btHandshake.hotspotPassword
        set(value) { btHandshake.hotspotPassword = value }

    /** Transport method: "native", "nearby", or "hotspot" */
    var directTransport: String = "native"

    /**
     * Start listening for incoming phone connections.
     */
    fun start() {
        if (serverJob?.isActive == true) return
        _connectionState.value = ConnectionState.DISCONNECTED

        OalLog.i(TAG, "Starting direct mode (transport=$directTransport)")

        // Start BT RFCOMM handshake for native and hotspot modes
        if (directTransport == "native" || directTransport == "hotspot") {
            btHandshake.start()
            OalLog.i(TAG, "BT RFCOMM handshake started")
        }

        // Start Nearby Connections discovery for nearby mode
        if (directTransport == "nearby") {
            _nearbyManager?.stop()
            _nearbyManager = AaNearbyManager(context, scope) { nearbySocket ->
                scope.launch(Dispatchers.IO) {
                    OalLog.i(TAG, "Nearby socket ready \u2014 starting AA session")
                    handleConnection(nearbySocket)
                }
            }.also { mgr ->
                mgr.defaultPhoneName = defaultPhoneName
                mgr.onPhoneConnected = onPhoneConnected
            }
            _nearbyManager?.start()
        }

        // Start WiFi Direct group for native mode — creates P2P AP,
        // feeds SSID/PSK to BT handshake manager automatically
        if (directTransport == "native") {
            wifiDirectManager?.stop()
            wifiDirectManager = AaWifiDirectManager(context)
            wifiDirectManager?.onCredentialsReady = { ssid, psk, ip, bssid ->
                OalLog.i(TAG, "WiFi Direct ready: SSID=$ssid IP=$ip")
                btHandshake.hotspotSsid = ssid
                btHandshake.hotspotPassword = psk
                // The BT handshake will use these when the phone connects
            }
            wifiDirectManager?.start()
        }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
                registerNsd()
                OalLog.i(TAG, "Listening on port $PORT")

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
        btHandshake.stop()
        _nearbyManager?.stop()
        _nearbyManager = null
        wifiDirectManager?.stop()
        wifiDirectManager = null
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
                // AA post-handshake: 4-byte header is NOT encrypted,
                // only the body (type + payload) is encrypted.
                val bodyLen = AaMessage.TYPE_SIZE + msg.payloadLength
                val plainBody = ByteArray(bodyLen)
                plainBody[0] = (msg.type shr 8).toByte()
                plainBody[1] = (msg.type and 0xFF).toByte()
                if (msg.payloadLength > 0) {
                    System.arraycopy(msg.payload, msg.payloadOffset, plainBody, 2, msg.payloadLength)
                }
                // SSLEngine is NOT thread-safe — encrypt + write must be atomic.
                // Multiple coroutines send ChannelOpenResponse simultaneously;
                // without this lock, concurrent encrypt() calls corrupt the
                // shared netOutBuffer and cause "encrypt error: null".
                synchronized(out) {
                    val encBody = sslEngine.encrypt(plainBody) ?: return@withContext
                    val header = ByteArray(4)
                    header[0] = msg.channel.toByte()
                    header[1] = msg.flags.toByte()
                    header[2] = (encBody.size shr 8).toByte()
                    header[3] = (encBody.size and 0xFF).toByte()
                    out.write(header)
                    out.write(encBody)
                    out.flush()
                }
            } else {
                synchronized(out) { codec.encode(msg, out) }
            }
        } catch (e: java.io.IOException) {
            // Don't spam log for pipe write errors during normal disconnect
            if (sslActive) OalLog.w(TAG, "Send IO error (pipe may be closing): ${e.message}")
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
            OalLog.i(TAG, "Starting version exchange — writing version request")
            codec.writeVersionRequest(outputStream!!)
            OalLog.i(TAG, "Version request sent — waiting for response (may block on NearbySocket latch)")
            val (major, minor) = codec.readVersionResponse(inputStream!!)
            OalLog.i(TAG, "Phone version: $major.$minor")

            // 2. SSL handshake
            OalLog.i(TAG, "Starting SSL handshake")
            if (!sslEngine.performHandshake(inputStream!!, outputStream!!)) {
                OalLog.e(TAG, "SSL handshake failed")
                _controlMessages.emit(ControlMessage.Error(7, "SSL handshake failed"))
                return
            }
            OalLog.i(TAG, "SSL handshake complete")

            // 3. Send AUTH_COMPLETE — must be PLAIN (before sslActive),
            //    matching aasdk/HURev behavior. This is the transition signal.
            val authComplete = AaMessage(AaChannel.CONTROL, 0x03,
                AaMsgType.AUTH_COMPLETE, byteArrayOf(0x08, 0x00))
            codec.encode(authComplete, outputStream!!)
            sslActive = true

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
            // Step 1: Read 4-byte AA header (NOT encrypted)
            val rawHeader = ByteArray(4)
            if (readFully(input, rawHeader, 4) < 0) {
                OalLog.i(TAG, "Phone disconnected (EOF)")
                return
            }


            val channel = rawHeader[0].toInt() and 0xFF
            val flags = rawHeader[1].toInt() and 0xFF
            val encLen = ((rawHeader[2].toInt() and 0xFF) shl 8) or (rawHeader[3].toInt() and 0xFF)

            if (encLen < 0 || encLen > 4 * 1024 * 1024) {
                OalLog.e(TAG, "Invalid enc_len: $encLen on ${AaChannel.name(channel)}")
                return
            }

            // For fragmented video (flag 0x09), consume 4-byte total-size prefix
            if (flags == 0x09) {
                val fragSizeBuf = ByteArray(4)
                if (readFully(input, fragSizeBuf, 4) < 0) return
            }

            // Step 2: Read enc_len bytes of encrypted body
            val encBody = ByteArray(encLen)
            if (encLen > 0 && readFully(input, encBody, encLen) < 0) {
                OalLog.i(TAG, "Phone disconnected during body read")
                return
            }

            // Step 3: Decrypt if SSL is active
            val plainBody: ByteArray = if (sslActive && encLen > 0) {
                sslEngine.decrypt(encBody) ?: run {
                    OalLog.e(TAG, "SSL decrypt failed on ${AaChannel.name(channel)}")
                    return
                }
            } else {
                encBody
            }

            if (plainBody.size < 2) {
                OalLog.w(TAG, "Skipping tiny message on ${AaChannel.name(channel)}: ${plainBody.size}B")
                continue
            }

            // Step 4: Parse type + payload from decrypted plaintext
            // CRITICAL: Only FIRST (0x09) and BULK (0x0b) frames have a 2-byte message type prefix.
            // MIDDLE (0x08) and LAST (0x0a) frames are continuation data with NO type prefix.
            // The frame type bits: bit 0 = FIRST, bit 1 = LAST, bit 2 = CONTROL, bit 3 = ENCRYPTED
            val isFirstOrBulk = (flags and 0x01) != 0  // FIRST bit set = FIRST or BULK frame
            val type: Int
            val msg: AaMessage
            if (isFirstOrBulk) {
                type = ((plainBody[0].toInt() and 0xFF) shl 8) or (plainBody[1].toInt() and 0xFF)
                msg = AaMessage(channel, flags, type, plainBody, 2, plainBody.size - 2)
            } else {
                // MIDDLE/LAST continuation — no type prefix, use type 0 (DATA) as convention
                type = 0
                msg = AaMessage(channel, flags, type, plainBody, 0, plainBody.size)
            }

            handleMessage(msg)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n < 0) return -1
            offset += n
        }
        return offset
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
                val response = DirectServiceDiscovery.build(
                    videoConfig, vehicleIdentity,
                    btMacAddress = btMacAddress,
                    useAacAudio = useAacAudio,
                    hideClock = hideClock, hideSignal = hideSignal, hideBattery = hideBattery,
                )
                sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.SERVICE_DISCOVERY_RESPONSE, response))
                OalLog.i(TAG, "ServiceDiscoveryResponse sent (${response.servicesCount} services)")
            }

            AaMsgType.PING_REQUEST -> {
                // Respond with PingResponse protobuf
                val response = Control.PingResponse.newBuilder()
                    .setTimestamp(System.nanoTime())
                    .build()
                sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.PING_RESPONSE, response))
                OalLog.d(TAG, "Ping response sent")
            }

            AaMsgType.BYEBYE_REQUEST -> {
                OalLog.i(TAG, "ByeByeRequest received")
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.BYEBYE_RESPONSE, ByteArray(0)))
                _controlMessages.emit(ControlMessage.PhoneDisconnected("byebye"))
            }

            AaMsgType.AUDIO_FOCUS_REQUEST -> {
                // Parse but don't log every request — they flood rapidly
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val request = Control.AudioFocusRequestNotification.parseFrom(data)
                    // Only log non-RELEASE requests to reduce noise
                    if (request.request != Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE) {
                        OalLog.d(TAG, "AudioFocusRequest: type=${request.request}")
                    }
                } catch (_: Exception) {}
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
                // Parse voice session state and emit for UI
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    // Voice session proto: field 1 = status (1=started, 2=finished)
                    val active = data.isNotEmpty() && data.size >= 2 && data[1].toInt() == 1
                    OalLog.i(TAG, "Voice session: ${if (active) "started" else "finished"}")
                    _controlMessages.emit(ControlMessage.VoiceSession(active))
                } catch (_: Exception) {
                    OalLog.i(TAG, "Voice session notification (unparsed)")
                }
            }

            else -> {
                OalLog.d(TAG, "Unhandled control msg type=${msg.type} (${msg.payloadLength}B)")
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

                // When SENSOR channel opens, immediately send DrivingStatus = UNRESTRICTED.
                // The phone needs this to know the head unit is ready. Without it,
                // the phone times out after ~2.5 minutes assuming the HU is unresponsive.
                // This matches HUR's behavior (AapControl.channelOpenRequest).
                if (msg.channel == AaChannel.SENSOR) {
                    val drivingStatus = Sensors.SensorBatch.newBuilder()
                        .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
                            .setStatus(1) // UNRESTRICTED
                            .build())
                        .build()
                    sendMessage(AaMessage.fromProto(AaChannel.SENSOR, AaMsgType.MEDIA_DATA, drivingStatus))
                    OalLog.i(TAG, "DrivingStatus UNRESTRICTED sent on SENSOR channel")
                }

                // Don't send VideoFocusNotification here — wait for MEDIA_SETUP.
                // HUR sends VideoFocus only after responding to MEDIA_SETUP with Config.
                // Sending it too early causes the phone to send a tiny probe frame before
                // the session is properly configured.
                if (msg.channel == AaChannel.VIDEO) {
                    _controlMessages.emit(ControlMessage.PhoneConnected("phone", "wireless"))
                }
            }

            AaMsgType.MEDIA_SETUP -> {
                OalLog.i(TAG, "MediaSetup on ${AaChannel.name(msg.channel)}")
                // Respond with proper Config protobuf — status=HEADUNIT, maxUnacked=30
                val configResponse = Media.Config.newBuilder()
                    .setStatus(Media.Config.ConfigStatus.HEADUNIT)
                    .setMaxUnacked(MAX_UNACKED)
                    .addConfigurationIndices(0)
                    .build()
                sendMessage(AaMessage.fromProto(msg.channel, AaMsgType.MEDIA_CONFIG, configResponse))
                OalLog.i(TAG, "MediaConfig sent on ${AaChannel.name(msg.channel)} (maxUnacked=$MAX_UNACKED)")

                // After video setup, send VideoFocusNotification to tell the phone to start streaming
                if (msg.channel == AaChannel.VIDEO) {
                    sendVideoFocusNotification()
                }

                // After audio setup, proactively send AudioFocusNotification (GAIN)
                // so the phone knows it's safe to start audio immediately
                if (AaChannel.isAudio(msg.channel)) {
                    val focusNotification = Control.AudioFocusNotification.newBuilder()
                        .setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
                        .setUnsolicited(true)
                        .build()
                    sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.AUDIO_FOCUS_NOTIFICATION, focusNotification))
                }
            }

            AaMsgType.MEDIA_START -> {
                // Parse session ID from MediaStart protobuf and store per-channel
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val start = Media.Start.parseFrom(data)
                    channelSessionIds[msg.channel] = start.sessionId
                    OalLog.i(TAG, "MediaStart on ${AaChannel.name(msg.channel)} (sessionId=${start.sessionId})")
                } catch (e: Exception) {
                    OalLog.i(TAG, "MediaStart on ${AaChannel.name(msg.channel)} (unparsed)")
                }
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
                // Log first few video frames for diagnostics
                val totalFrames = (unackedFrames + 1).toLong()
                if (totalFrames <= 3) {
                    val hexPrefix = msg.payload.copyOfRange(
                        msg.payloadOffset,
                        minOf(msg.payloadOffset + minOf(msg.payloadLength, 16), msg.payload.size)
                    ).joinToString(" ") { "%02x".format(it) }
                    OalLog.i(TAG, "Video MEDIA_DATA #$totalFrames: ${msg.payloadLength}B flags=0x${msg.flags.toString(16)} hex=[$hexPrefix]")
                }
                val frame = videoAssembler?.process(msg)
                if (frame != null) {
                    _videoFrames.emit(frame)
                    // Send media ack
                    unackedFrames++
                    if (unackedFrames >= MAX_UNACKED / 2) {
                        val ack = Media.Ack.newBuilder()
                            .setSessionId(channelSessionIds[AaChannel.VIDEO] ?: 1)
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

            else -> {
                OalLog.d(TAG, "Video channel msg type=0x${msg.type.toString(16)} (${msg.payloadLength}B)")
                handleChannelControl(msg)
            }
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
            // Audio MEDIA_DATA has an 8-byte timestamp prefix before the actual audio data
            // (same as video). Skip it — the audio player doesn't need timestamps.
            val audioOffset = msg.payloadOffset + 8
            val audioLength = msg.payloadLength - 8
            if (audioLength <= 0) return
            val audioData = msg.payload.copyOfRange(audioOffset, audioOffset + audioLength)
            _audioFrames.emit(AudioFrame(
                direction = AudioFrame.DIRECTION_PLAYBACK,
                purpose = purpose,
                sampleRate = if (msg.channel == AaChannel.AUDIO_MEDIA) 48000 else 16000,
                channels = if (msg.channel == AaChannel.AUDIO_MEDIA) 2 else 1,
                data = audioData,
                isAac = useAacAudio,
            ))

            // Ack audio
            val ack = Media.Ack.newBuilder().setSessionId(channelSessionIds[msg.channel] ?: 1).setAck(1).build()
            sendMessage(AaMessage.fromProto(msg.channel, AaMsgType.MEDIA_ACK, ack))
        } else {
            handleChannelControl(msg)
        }
    }

    // Track last turn detail for combining with distance events
    private var lastTurnDetail: com.openautolink.app.proto.NavigationStatus.NextTurnDetail? = null

    private suspend fun handleNavigation(msg: AaMessage) {
        val navDetailType = 0x8004  // NEXTTURNDETAILS
        val navDistType = 0x8005    // NEXTTURNDISTANCEANDTIME

        when (msg.type) {
            navDetailType -> {
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val detail = com.openautolink.app.proto.NavigationStatus.NextTurnDetail.parseFrom(data)
                    lastTurnDetail = detail
                    val maneuver = mapNextEvent(detail.nextturn)
                    _controlMessages.emit(ControlMessage.NavState(
                        maneuver = maneuver,
                        distanceMeters = null,
                        road = detail.road,
                        etaSeconds = null,
                        roundaboutExitNumber = if (detail.hasTrunnumer()) detail.trunnumer.toInt() else null,
                        roundaboutExitAngle = if (detail.hasTurnangel()) detail.turnangel.toInt() else null,
                    ))
                    OalLog.d(TAG, "Nav: turn=$maneuver road=${detail.road}")
                } catch (e: Exception) {
                    OalLog.e(TAG, "Nav detail parse error: ${e.message}")
                }
            }

            navDistType -> {
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val event = com.openautolink.app.proto.NavigationStatus.NextTurnDistanceEvent.parseFrom(data)
                    val detail = lastTurnDetail
                    _controlMessages.emit(ControlMessage.NavState(
                        maneuver = detail?.let { mapNextEvent(it.nextturn) },
                        distanceMeters = if (event.hasDistance()) event.distance.toInt() else null,
                        road = detail?.road,
                        etaSeconds = if (event.hasTime()) event.time.toInt() else null,
                    ))
                } catch (e: Exception) {
                    OalLog.e(TAG, "Nav distance parse error: ${e.message}")
                }
            }

            else -> handleChannelControl(msg)
        }
    }

    private fun mapNextEvent(event: com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (event) {
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.DEPARTE -> "depart"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> "name_change"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> "slight_turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.TURN -> "turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> "sharp_turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.UTURN -> "u_turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ONRAMPE -> "on_ramp"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> "off_ramp"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.FORME -> "fork"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.MERGE -> "merge"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> "roundabout_enter"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> "roundabout_exit"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> "roundabout_enter_exit"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.STRAIGHTE -> "straight"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> "ferry_boat"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAINE -> "ferry_train"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> "destination"
            else -> "unknown"
        }
    }

    private suspend fun handleMediaPlayback(msg: AaMessage) {
        when (msg.type) {
            0x8001, 0x8003 -> {
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val metadata = com.openautolink.app.proto.MediaPlayback.MediaMetaData.parseFrom(data)
                    val title = metadata.song.takeIf { it.isNotEmpty() }
                    val artist = metadata.artist.takeIf { it.isNotEmpty() }
                    val album = metadata.album.takeIf { it.isNotEmpty() }
                    OalLog.d(TAG, "Media: $title - $artist")
                    emitMediaMetadata(title, artist, album)
                } catch (e: Exception) {
                    OalLog.e(TAG, "MediaPlayback parse error: ${e.message}")
                }
            }
            else -> handleChannelControl(msg)
        }
    }

    /** Isolated method to avoid Kotlin type inference issues with sealed class + protobuf. */
    private suspend fun emitMediaMetadata(title: String?, artist: String?, album: String?) {
        _controlMessages.emit(ControlMessage.MediaMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = null,
            positionMs = null,
            playing = null
        ))
    }

    private suspend fun handlePhoneStatus(msg: AaMessage) {
        if (msg.type == AaMsgType.MEDIA_DATA || msg.type == AaMsgType.MEDIA_START) {
            try {
                val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                val status = Control.Service.PhoneStatusService.parseFrom(data)
                val calls = status.callsList.map { call ->
                    ControlMessage.PhoneCall(
                        state = call.state?.name ?: "UNKNOWN",
                        durationSeconds = if (call.hasCallDurationSeconds()) call.callDurationSeconds.toInt() else 0,
                        callerNumber = if (call.hasCallerNumber()) call.callerNumber else null,
                        callerId = if (call.hasCallerId()) call.callerId else null,
                    )
                }
                val signalStrength = if (status.hasSignalStrength()) status.signalStrength.toInt() else null
                OalLog.d(TAG, "PhoneStatus: signal=$signalStrength calls=${calls.size}")
                _controlMessages.emit(ControlMessage.PhoneStatus(signalStrength, calls))
            } catch (e: Exception) {
                OalLog.d(TAG, "PhoneStatus parse error (${msg.payloadLength}B): ${e.message}")
            }
        } else {
            handleChannelControl(msg)
        }
    }

    private suspend fun sendVideoFocusNotification() {
        // VIDEO_FOCUS_PROJECTED = "show me the phone's projected AA UI — send video"
        // VIDEO_FOCUS_NATIVE = "I want my own native UI — stop sending video"
        val focus = Media.VideoFocusNotification.newBuilder()
            .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED)
            .setUnsolicited(false)
            .build()
        sendMessage(AaMessage.fromProto(AaChannel.VIDEO, AaMsgType.VIDEO_FOCUS_NOTIFICATION, focus))
        OalLog.i(TAG, "VideoFocusNotification sent (PROJECTED)")
    }

    /** Request a keyframe (IDR) from the phone by re-sending VideoFocusNotification. */
    suspend fun requestKeyframe() {
        sendVideoFocusNotification()
    }

    // â”€â”€ NSD (Network Service Discovery) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Send mic audio PCM data to the phone on channel 7 (MIC).
     */
    suspend fun sendMicAudio(pcmData: ByteArray) {
        if (!sslActive) return
        sendMessage(AaMessage.raw(AaChannel.MIC, AaMsgType.MEDIA_DATA, pcmData))
    }

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
