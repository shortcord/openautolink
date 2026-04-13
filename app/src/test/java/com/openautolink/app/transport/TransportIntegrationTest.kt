package com.openautolink.app.transport

import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for the transport layer against a real TCP mock server.
 * Tests actual TCP connections, binary header serialization, and JSON control protocol.
 * Runs in ./gradlew testDebugUnitTest â€” no emulator needed.
 *
 * Uses direct socket reads with timeouts to avoid Flow/Turbine blocking IO hangs.
 */
class TransportIntegrationTest {

    private lateinit var server: MockOalBridgeServer

    @Before
    fun setUp() {
        server = MockOalBridgeServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // â”€â”€ Control channel tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test(timeout = 10_000)
    fun `control channel connects and receives hello`() = runBlocking {
        val socket = connectTo(server.controlPort)
        try {
            assertTrue(server.awaitControlConnected())
            server.sendHello()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val msg = readControlMessage(reader)
            assertTrue("Expected Hello, got $msg", msg is ControlMessage.Hello)
            val hello = msg as ControlMessage.Hello
            assertEquals(1, hello.version)
            assertEquals("MockBridge", hello.name)
            assertEquals(listOf("h264"), hello.capabilities)
            assertEquals(server.videoPort, hello.videoPort)
            assertEquals(server.audioPort, hello.audioPort)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel sends app hello`() = runBlocking {
        val channel = TcpControlChannel()
        try {
            withContext(Dispatchers.IO) {
                channel.connect("127.0.0.1", server.controlPort)
            }
            assertTrue(server.awaitControlConnected())

            channel.send(ControlMessage.AppHello(
                version = 1, name = "OpenAutoLink App",
                displayWidth = 2628, displayHeight = 800, displayDpi = 160
            ))

            // AppHello serializes as type=hello, so server deserializes as Hello
            val received = server.awaitReceivedMessage(ControlMessage.Hello::class.java)
            assertNotNull("Server should have received hello (serialized from AppHello)", received)
            val parsed = received as ControlMessage.Hello
            assertEquals(1, parsed.version)
            assertEquals("OpenAutoLink App", parsed.name)
        } finally {
            channel.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel receives phone_connected and phone_disconnected`() = runBlocking {
        val socket = connectTo(server.controlPort)
        try {
            assertTrue(server.awaitControlConnected())
            server.sendPhoneConnected("Pixel 10")
            server.sendPhoneDisconnected("user_disconnect")

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val msg1 = readControlMessage(reader)
            val msg2 = readControlMessage(reader)
            assertTrue(msg1 is ControlMessage.PhoneConnected)
            assertEquals("Pixel 10", (msg1 as ControlMessage.PhoneConnected).phoneName)
            assertTrue(msg2 is ControlMessage.PhoneDisconnected)
            assertEquals("user_disconnect", (msg2 as ControlMessage.PhoneDisconnected).reason)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel receives audio_start and audio_stop`() = runBlocking {
        val socket = connectTo(server.controlPort)
        try {
            assertTrue(server.awaitControlConnected())
            server.sendAudioStart("media", 48000, 2)
            server.sendAudioStop("media")

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val msg1 = readControlMessage(reader)
            val msg2 = readControlMessage(reader)
            assertTrue(msg1 is ControlMessage.AudioStart)
            assertEquals(AudioPurpose.MEDIA, (msg1 as ControlMessage.AudioStart).purpose)
            assertEquals(48000, msg1.sampleRate)
            assertEquals(2, msg1.channels)
            assertTrue(msg2 is ControlMessage.AudioStop)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel receives nav_state`() = runBlocking {
        val socket = connectTo(server.controlPort)
        try {
            assertTrue(server.awaitControlConnected())
            server.sendNavState("turn_left", 200, "Oak Ave", 380)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val msg = readControlMessage(reader)
            assertTrue(msg is ControlMessage.NavState)
            val nav = msg as ControlMessage.NavState
            assertEquals("turn_left", nav.maneuver)
            assertEquals(200, nav.distanceMeters)
            assertEquals("Oak Ave", nav.road)
            assertEquals(380, nav.etaSeconds)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel receives media_metadata`() = runBlocking {
        val socket = connectTo(server.controlPort)
        try {
            assertTrue(server.awaitControlConnected())
            server.sendMediaMetadata("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354000, 60000, true)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val msg = readControlMessage(reader)
            assertTrue(msg is ControlMessage.MediaMetadata)
            val meta = msg as ControlMessage.MediaMetadata
            assertEquals("Bohemian Rhapsody", meta.title)
            assertEquals("Queen", meta.artist)
            assertEquals(354000L, meta.durationMs)
            assertEquals(60000L, meta.positionMs)
            assertEquals(true, meta.playing)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel receives error message`() = runBlocking {
        val socket = connectTo(server.controlPort)
        try {
            assertTrue(server.awaitControlConnected())
            server.sendError(42, "phone lost")

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val msg = readControlMessage(reader)
            assertTrue(msg is ControlMessage.Error)
            assertEquals(42, (msg as ControlMessage.Error).code)
            assertEquals("phone lost", msg.message)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel sends touch event`() = runBlocking {
        val channel = TcpControlChannel()
        try {
            withContext(Dispatchers.IO) {
                channel.connect("127.0.0.1", server.controlPort)
            }
            assertTrue(server.awaitControlConnected())

            channel.send(ControlMessage.Touch(
                action = 0, x = 500.0f, y = 300.0f, pointerId = 0, pointers = null
            ))

            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && server.receivedControlLines.isEmpty()) {
                Thread.sleep(50)
            }
            assertTrue("Server should have received touch", server.receivedControlLines.isNotEmpty())
            assertTrue(server.receivedControlLines.first().contains("\"type\":\"touch\""))
        } finally {
            channel.close()
        }
    }

    @Test(timeout = 10_000)
    fun `control channel sends keyframe_request`() = runBlocking {
        val channel = TcpControlChannel()
        try {
            withContext(Dispatchers.IO) {
                channel.connect("127.0.0.1", server.controlPort)
            }
            assertTrue(server.awaitControlConnected())

            channel.send(ControlMessage.KeyframeRequest)

            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && server.receivedControlLines.isEmpty()) {
                Thread.sleep(50)
            }
            assertTrue(server.receivedControlLines.isNotEmpty())
            assertTrue(server.receivedControlLines.first().contains("\"keyframe_request\""))
        } finally {
            channel.close()
        }
    }

    // â”€â”€ Video channel tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test(timeout = 10_000)
    fun `video channel receives codec config frame`() = runBlocking {
        val socket = connectTo(server.videoPort)
        try {
            assertTrue(server.awaitVideoConnected())

            val spsData = MockOalBridgeServer.MINIMAL_SPS_PPS
            server.sendCodecConfig(1920, 1080, spsData)

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            val frame = readVideoFrame(stream)
            assertTrue("Expected codec config flag", frame.isCodecConfig)
            assertEquals(1920, frame.width)
            assertEquals(1080, frame.height)
            assertEquals(0L, frame.ptsMs)
            assertArrayEquals(spsData, frame.data)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `video channel receives IDR keyframe`() = runBlocking {
        val socket = connectTo(server.videoPort)
        try {
            assertTrue(server.awaitVideoConnected())

            val idrData = MockOalBridgeServer.MINIMAL_IDR
            server.sendKeyframe(1920, 1080, 0, idrData)

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            val frame = readVideoFrame(stream)
            assertTrue("Expected keyframe flag", frame.isKeyframe)
            assertEquals(1920, frame.width)
            assertEquals(1080, frame.height)
            assertArrayEquals(idrData, frame.data)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `video channel receives config then IDR then P-frame sequence`() = runBlocking {
        val socket = connectTo(server.videoPort)
        try {
            assertTrue(server.awaitVideoConnected())

            server.sendCodecConfig()
            server.sendKeyframe(ptsMs = 0)
            server.sendPFrame(ptsMs = 33)

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            val f1 = readVideoFrame(stream)
            val f2 = readVideoFrame(stream)
            val f3 = readVideoFrame(stream)
            assertTrue(f1.isCodecConfig)
            assertTrue(f2.isKeyframe)
            assertEquals(0L, f2.ptsMs)
            assertTrue(!f3.isKeyframe && !f3.isCodecConfig)
            assertEquals(33L, f3.ptsMs)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `video channel parses large payload correctly`() = runBlocking {
        val socket = connectTo(server.videoPort)
        try {
            assertTrue(server.awaitVideoConnected())

            val largePayload = ByteArray(100_000) { (it % 256).toByte() }
            server.sendVideoFrame(2560, 1440, 1000, VideoFrame.FLAG_KEYFRAME, largePayload)

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            val frame = readVideoFrame(stream)
            assertEquals(2560, frame.width)
            assertEquals(1440, frame.height)
            assertEquals(1000L, frame.ptsMs)
            assertTrue(frame.isKeyframe)
            assertEquals(100_000, frame.data.size)
            assertArrayEquals(largePayload, frame.data)
        } finally {
            socket.close()
        }
    }

    // â”€â”€ Audio channel tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test(timeout = 10_000)
    fun `audio channel receives media playback frame`() = runBlocking {
        val socket = connectTo(server.audioPort)
        try {
            assertTrue(server.awaitAudioConnected())

            val pcm = ByteArray(3840) { (it % 128).toByte() }
            server.sendAudioFrame(AudioPurpose.MEDIA, 48000, 2, pcm)

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            val frame = readAudioFrame(stream)
            assertTrue(frame.isPlayback)
            assertEquals(AudioPurpose.MEDIA, frame.purpose)
            assertEquals(48000, frame.sampleRate)
            assertEquals(2, frame.channels)
            assertEquals(3840, frame.data.size)
            assertArrayEquals(pcm, frame.data)
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `audio channel receives all 5 purpose types`() = runBlocking {
        val socket = connectTo(server.audioPort)
        try {
            assertTrue(server.awaitAudioConnected())

            val purposes = listOf(
                AudioPurpose.MEDIA to Pair(48000, 2),
                AudioPurpose.NAVIGATION to Pair(16000, 1),
                AudioPurpose.ASSISTANT to Pair(16000, 1),
                AudioPurpose.PHONE_CALL to Pair(8000, 1),
                AudioPurpose.ALERT to Pair(24000, 1),
            )

            for ((purpose, config) in purposes) {
                val pcm = ByteArray(100) { purpose.ordinal.toByte() }
                server.sendAudioFrame(purpose, config.first, config.second, pcm)
            }

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            for ((purpose, config) in purposes) {
                val frame = readAudioFrame(stream)
                assertEquals("Purpose mismatch", purpose, frame.purpose)
                assertEquals("Sample rate mismatch for $purpose", config.first, frame.sampleRate)
                assertEquals("Channels mismatch for $purpose", config.second, frame.channels)
            }
        } finally {
            socket.close()
        }
    }

    @Test(timeout = 10_000)
    fun `audio channel receives navigation mono 16kHz frame`() = runBlocking {
        val socket = connectTo(server.audioPort)
        try {
            assertTrue(server.awaitAudioConnected())

            val pcm = ByteArray(640)
            server.sendAudioFrame(AudioPurpose.NAVIGATION, 16000, 1, pcm)

            val stream = DataInputStream(socket.getInputStream().buffered(65536))
            val frame = readAudioFrame(stream)
            assertEquals(AudioPurpose.NAVIGATION, frame.purpose)
            assertEquals(16000, frame.sampleRate)
            assertEquals(1, frame.channels)
            assertEquals(640, frame.data.size)
        } finally {
            socket.close()
        }
    }

    // â”€â”€ Full handshake sequence test â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test(timeout = 15_000)
    fun `full handshake sequence - hello exchange then phone connect`() = runBlocking {
        // Client side: connect and send app hello via TcpControlChannel
        val channel = TcpControlChannel()
        try {
            withContext(Dispatchers.IO) {
                channel.connect("127.0.0.1", server.controlPort)
            }
            assertTrue(server.awaitControlConnected())

            // Bridge sends messages
            server.sendHello()
            server.sendConfigEcho()
            server.sendPhoneConnected()
            server.sendAudioStart()

            // App sends hello back
            channel.send(ControlMessage.AppHello(1, "TestApp", 1920, 1080, 160))

            // Read from socket directly to avoid blocking Flow
            val socket = channel.javaClass.getDeclaredField("socket")
                .apply { isAccessible = true }.get(channel) as Socket
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            val msg1 = readControlMessage(reader)
            val msg2 = readControlMessage(reader)
            val msg3 = readControlMessage(reader)
            val msg4 = readControlMessage(reader)

            assertTrue("Expected Hello", msg1 is ControlMessage.Hello)
            assertTrue("Expected ConfigEcho", msg2 is ControlMessage.ConfigEcho)
            assertTrue("Expected PhoneConnected", msg3 is ControlMessage.PhoneConnected)
            assertTrue("Expected AudioStart", msg4 is ControlMessage.AudioStart)

            // AppHello serializes as type=hello, so server deserializes as Hello
            val received = server.awaitReceivedMessage(ControlMessage.Hello::class.java)
            assertNotNull("Server should have received hello (from AppHello)", received)
        } finally {
            channel.close()
        }
    }

    // ── Mic audio send test ──────────────────────────────────────────

    @Test(timeout = 10_000)
    fun `audio channel sends mic frame to bridge`() = runBlocking {
        val channel = TcpAudioChannel()
        try {
            withContext(Dispatchers.IO) {
                channel.connect("127.0.0.1", server.audioPort)
            }
            assertTrue(server.awaitAudioConnected())

            // Send a mic frame (direction=1, purpose=ASSISTANT, 16kHz mono)
            val micPcm = ByteArray(640) { (it % 64).toByte() }
            val micFrame = AudioFrame(
                AudioFrame.DIRECTION_MIC,
                AudioPurpose.ASSISTANT,
                16000,
                1,
                micPcm
            )
            channel.sendFrame(micFrame)

            // Verify server received it
            val received = server.awaitReceivedAudioFrame()
            assertNotNull("Server should have received mic frame", received)
            assertEquals(AudioFrame.DIRECTION_MIC, received!!.direction)
            assertEquals(AudioPurpose.ASSISTANT, received.purpose)
            assertEquals(16000, received.sampleRate)
            assertEquals(1, received.channels)
            assertEquals(640, received.data.size)
            assertArrayEquals(micPcm, received.data)
        } finally {
            channel.close()
        }
    }

    @Test(timeout = 10_000)
    fun `audio channel sends phone call mic frame`() = runBlocking {
        val channel = TcpAudioChannel()
        try {
            withContext(Dispatchers.IO) {
                channel.connect("127.0.0.1", server.audioPort)
            }
            assertTrue(server.awaitAudioConnected())

            // Phone call mic: direction=1, purpose=PHONE_CALL, 8kHz mono
            val micPcm = ByteArray(320) { 0x42 }
            val micFrame = AudioFrame(
                AudioFrame.DIRECTION_MIC,
                AudioPurpose.PHONE_CALL,
                8000,
                1,
                micPcm
            )
            channel.sendFrame(micFrame)

            val received = server.awaitReceivedAudioFrame()
            assertNotNull("Server should have received phone call mic frame", received)
            assertEquals(AudioFrame.DIRECTION_MIC, received!!.direction)
            assertEquals(AudioPurpose.PHONE_CALL, received.purpose)
            assertEquals(8000, received.sampleRate)
            assertEquals(1, received.channels)
            assertEquals(320, received.data.size)
        } finally {
            channel.close()
        }
    }

    // ── ConnectionManager reconnect test ─────────────────────────────

    @Test(timeout = 15_000)
    fun `ConnectionManager reconnects after control channel drops`() = runBlocking {
        // ConnectionManager launches coroutines — needs its own scope on Dispatchers.IO
        // to avoid blocking the runBlocking event loop
        val ioScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val connMgr = ConnectionManager(ioScope)
        val resolveCount = AtomicInteger(0)

        try {
            // Connect to mock server
            connMgr.connect(
                "127.0.0.1",
                server.controlPort,
                networkResolver = NetworkResolver {
                    resolveCount.incrementAndGet()
                    null
                }
            )

            assertTrue("Should connect to control", server.awaitControlConnected(5000))

            // Send hello so state transitions to CONNECTED
            server.sendHello()

            // Wait for CONNECTED state
            withTimeout(5000) {
                while (connMgr.connectionState.value != ConnectionState.CONNECTED) {
                    kotlinx.coroutines.delay(50)
                }
            }
            assertEquals(ConnectionState.CONNECTED, connMgr.connectionState.value)

            // Record ports before stopping server
            val ctlPort = server.controlPort
            val vidPort = server.videoPort
            val audPort = server.audioPort

            // Drop the connection server-side
            server.stop()

            // Wait for DISCONNECTED state
            withTimeout(5000) {
                while (connMgr.connectionState.value != ConnectionState.DISCONNECTED) {
                    kotlinx.coroutines.delay(50)
                }
            }
            assertEquals(ConnectionState.DISCONNECTED, connMgr.connectionState.value)

            // Start a new server on the SAME ports (simulating bridge restart)
            val server2 = MockOalBridgeServer()
            server2.startOnPorts(ctlPort, vidPort, audPort)

            try {
                // ConnectionManager should reconnect within backoff window
                assertTrue("Should reconnect", server2.awaitControlConnected(10_000))

                // Send hello again
                server2.sendHello()

                withTimeout(5000) {
                    while (connMgr.connectionState.value != ConnectionState.CONNECTED) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                assertEquals(ConnectionState.CONNECTED, connMgr.connectionState.value)
                assertTrue("Network resolver should run again on reconnect", resolveCount.get() >= 2)
            } finally {
                server2.stop()
            }
        } finally {
            connMgr.disconnect()
            ioScope.cancel()
        }
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun connectTo(port: Int): Socket = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).apply { tcpNoDelay = true; soTimeout = 5000 }
    }

    private fun readControlMessage(reader: BufferedReader): ControlMessage {
        val line = reader.readLine() ?: throw AssertionError("Connection closed")
        return ControlMessageSerializer.deserialize(line) ?: throw AssertionError("Failed to parse: $line")
    }

    private fun readVideoFrame(stream: DataInputStream): VideoFrame {
        val hdr = ByteArray(16)
        stream.readFully(hdr)
        val payloadLen = readU32LE(hdr, 0)
        val width = readU16LE(hdr, 4)
        val height = readU16LE(hdr, 6)
        val ptsMs = readU32LE(hdr, 8)
        val flags = readU16LE(hdr, 12)
        val payload = if (payloadLen > 0) ByteArray(payloadLen.toInt()).also { stream.readFully(it) } else ByteArray(0)
        return VideoFrame(width, height, ptsMs, flags, payload)
    }

    private fun readAudioFrame(stream: DataInputStream): AudioFrame {
        val hdr = ByteArray(8)
        stream.readFully(hdr)
        val direction = hdr[0].toInt() and 0xFF
        val purposeByte = hdr[1].toInt() and 0xFF
        val sampleRate = readU16LE(hdr, 2)
        val channels = hdr[4].toInt() and 0xFF
        val payloadLen = readU24LE(hdr, 5)
        val purpose = AudioFrame.purposeFromByte(purposeByte)
            ?: throw AssertionError("Unknown purpose: $purposeByte")
        val payload = if (payloadLen > 0) ByteArray(payloadLen).also { stream.readFully(it) } else ByteArray(0)
        return AudioFrame(direction, purpose, sampleRate, channels, payload)
    }

    private fun readU16LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(buf: ByteArray, off: Int): Long =
        (buf[off].toInt() and 0xFF).toLong() or
        ((buf[off + 1].toInt() and 0xFF).toLong() shl 8) or
        ((buf[off + 2].toInt() and 0xFF).toLong() shl 16) or
        ((buf[off + 3].toInt() and 0xFF).toLong() shl 24)

    private fun readU24LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        ((buf[off + 2].toInt() and 0xFF) shl 16)
}
