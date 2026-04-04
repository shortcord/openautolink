package com.openautolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.openautolink.app.transport.AudioPurpose
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One AudioTrack + AudioRingBuffer per audio purpose.
 * Pre-allocated at session start, reused across start/stop cycles.
 *
 * The playback thread drains the ring buffer into the AudioTrack continuously
 * when active. On underrun, writes silence to keep the stream alive.
 */
class AudioPurposeSlot(
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channelCount: Int,
    private val bufferDurationMs: Int = 500
) {
    companion object {
        private const val TAG = "AudioPurposeSlot"
        private const val DRAIN_CHUNK_MS = 20 // Write 20ms chunks to AudioTrack
        private const val TRACK_BUFFER_MULTIPLIER = 4 // 4x minimum buffer for jitter tolerance
    }

    private var audioTrack: AudioTrack? = null
    private var ringBuffer: AudioRingBuffer? = null
    private var playbackThread: Thread? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)

    /**
     * Create the AudioTrack and ring buffer. Does NOT start playback.
     */
    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufSize = maxOf(minBufSize * TRACK_BUFFER_MULTIPLIER, 16384)

        val attributes = buildAudioAttributes(purpose)
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Ring buffer: 500ms capacity
        val bytesPerSample = 2 // 16-bit PCM
        val ringCapacity = sampleRate * channelCount * bytesPerSample * bufferDurationMs / 1000
        ringBuffer = AudioRingBuffer(ringCapacity)

        Log.d(TAG, "Initialized $purpose slot: ${sampleRate}Hz ${channelCount}ch, " +
                "track=${trackBufSize}B, ring=${ringCapacity}B")
    }

    /**
     * Start the playback drain thread. AudioTrack begins playing.
     */
    fun start() {
        if (released.get() || active.get()) return
        val track = audioTrack ?: return

        active.set(true)
        track.play()

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "AudioSlot-${purpose.name}").also { it.start() }

        Log.d(TAG, "$purpose playback started")
    }

    /**
     * Stop playback (pause AudioTrack, stop thread). Does NOT release resources.
     * Clears ring buffer — use for explicit stop (phone disconnect, purpose stop).
     */
    fun stop() {
        pausedByFocusLoss.set(false)
        if (!active.getAndSet(false)) return

        playbackThread?.join(1000)
        playbackThread = null

        audioTrack?.pause()
        audioTrack?.flush()
        ringBuffer?.clear()

        Log.d(TAG, "$purpose playback stopped")
    }

    /**
     * Pause playback without clearing ring buffer.
     * Used on audio focus loss — keeps data flowing into ring buffer
     * (overflow drops oldest naturally), resumes cleanly on focus regain.
     */
    fun pause() {
        if (!active.getAndSet(false)) return

        pausedByFocusLoss.set(true)
        playbackThread?.join(1000)
        playbackThread = null

        audioTrack?.pause()
        // Do NOT flush or clear ring buffer — resume will pick up from here

        Log.d(TAG, "$purpose playback paused (focus loss)")
    }

    /**
     * Resume playback after pause (audio focus regain).
     * Only resumes if paused by focus loss, not if explicitly stopped.
     */
    fun resume() {
        if (released.get() || active.get()) return
        if (!pausedByFocusLoss.getAndSet(false)) return
        val track = audioTrack ?: return

        active.set(true)
        track.play()

        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "AudioSlot-${purpose.name}").also { it.start() }

        Log.d(TAG, "$purpose playback resumed (focus regain)")
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    /**
     * Feed PCM data into the ring buffer. Called from the TCP read thread.
     * Non-blocking — ring buffer handles overflow by dropping oldest.
     */
    fun feedPcm(data: ByteArray) {
        ringBuffer?.write(data)
    }

    /** Set stereo volume (0.0 to 1.0). */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    /** Release all resources. Slot cannot be reused after this. */
    fun release() {
        if (released.getAndSet(true)) return
        stop()
        audioTrack?.release()
        audioTrack = null
        ringBuffer = null
        Log.d(TAG, "$purpose slot released")
    }

    val isActive: Boolean get() = active.get()

    private fun drainLoop() {
        val track = audioTrack ?: return
        val ring = ringBuffer ?: return
        // Drain chunk: 20ms of audio — larger chunks reduce write call overhead
        val bytesPerSample = 2
        val chunkBytes = sampleRate * channelCount * bytesPerSample * DRAIN_CHUNK_MS / 1000
        val chunk = ByteArray(chunkBytes)
        val silence = ByteArray(chunkBytes)

        while (active.get()) {
            val available = ring.available
            if (available >= chunkBytes) {
                // Enough data — read a full chunk and write
                val read = ring.read(chunk)
                if (read > 0) {
                    var offset = 0
                    while (offset < read && active.get()) {
                        val written = track.write(chunk, offset, read - offset,
                            AudioTrack.WRITE_NON_BLOCKING)
                        if (written > 0) {
                            offset += written
                            framesWritten.addAndGet(written.toLong() / (channelCount * bytesPerSample))
                        } else if (written == 0) {
                            // AudioTrack buffer full — yield briefly
                            Thread.sleep(1)
                        } else {
                            break // Error
                        }
                    }
                }
            } else if (available > 0) {
                // Partial data available — write what we have to minimize latency
                val partial = ByteArray(available)
                val read = ring.read(partial)
                if (read > 0) {
                    track.write(partial, 0, read, AudioTrack.WRITE_NON_BLOCKING)
                    framesWritten.addAndGet(read.toLong() / (channelCount * bytesPerSample))
                }
            } else {
                // Ring buffer empty — write silence to keep stream alive, yield
                underrunCount.incrementAndGet()
                track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                Thread.sleep(DRAIN_CHUNK_MS.toLong() / 2)
            }
        }
    }

    private fun buildAudioAttributes(purpose: AudioPurpose): AudioAttributes {
        val usage = when (purpose) {
            AudioPurpose.MEDIA -> AudioAttributes.USAGE_MEDIA
            AudioPurpose.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioPurpose.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            AudioPurpose.PHONE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            AudioPurpose.ALERT -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
        val contentType = when (purpose) {
            AudioPurpose.MEDIA -> AudioAttributes.CONTENT_TYPE_MUSIC
            AudioPurpose.PHONE_CALL -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.ASSISTANT -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.NAVIGATION -> AudioAttributes.CONTENT_TYPE_SPEECH
            AudioPurpose.ALERT -> AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
    }
}
