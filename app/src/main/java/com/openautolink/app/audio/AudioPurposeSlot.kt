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
 * Based on app_v1's production-proven approach:
 * - Ring buffer absorbs TCP/network jitter (500ms capacity)
 * - Dedicated URGENT_AUDIO thread drains at steady rate
 * - Pre-fill 80ms before calling AudioTrack.play()
 * - Non-blocking writes with residual tracking (no data loss)
 * - Steady 10ms write pacing from dedicated thread
 */
class AudioPurposeSlot(
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channelCount: Int,
    private val bufferDurationMs: Int = 500
) {
    companion object {
        private const val TAG = "AudioPurposeSlot"
        private const val PREFILL_MS = 200  // Buffer 200ms before starting
        private const val DRAIN_CHUNK_MS = 200 // Write 200ms chunks — reduces write call overhead
    }

    private var audioTrack: AudioTrack? = null
    private var ringBuffer: AudioRingBuffer? = null
    private var playbackThread: Thread? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)
    @Volatile private var trackPlaying = false

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)

    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufSize = maxOf(minBufSize * 8, 76800) // ~200ms at 48kHz stereo

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(buildAudioAttributes(purpose))
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val bytesPerSample = 2
        val ringCapacity = sampleRate * channelCount * bytesPerSample * bufferDurationMs / 1000
        ringBuffer = AudioRingBuffer(ringCapacity)

        Log.d(TAG, "Initialized $purpose: ${sampleRate}Hz ${channelCount}ch, " +
                "track=${trackBufSize}B, ring=${ringCapacity}B")
    }

    fun start() {
        if (released.get() || active.get()) return
        audioTrack ?: return
        active.set(true)
        trackPlaying = false
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "AudioSlot-${purpose.name}").also { it.start() }
        Log.d(TAG, "$purpose active (prefilling ${PREFILL_MS}ms)")
    }

    fun stop() {
        pausedByFocusLoss.set(false)
        if (!active.getAndSet(false)) return
        playbackThread?.join(1000)
        playbackThread = null
        audioTrack?.pause()
        audioTrack?.flush()
        ringBuffer?.clear()
        trackPlaying = false
        Log.d(TAG, "$purpose stopped")
    }

    fun pause() {
        if (!active.getAndSet(false)) return
        pausedByFocusLoss.set(true)
        playbackThread?.join(1000)
        playbackThread = null
        audioTrack?.pause()
        trackPlaying = false
        Log.d(TAG, "$purpose paused (focus loss)")
    }

    fun resume() {
        if (released.get() || active.get()) return
        if (!pausedByFocusLoss.getAndSet(false)) return
        audioTrack ?: return
        active.set(true)
        trackPlaying = false
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            drainLoop()
        }, "AudioSlot-${purpose.name}").also { it.start() }
        Log.d(TAG, "$purpose resumed")
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    fun feedPcm(data: ByteArray) {
        ringBuffer?.write(data)
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun release() {
        if (released.getAndSet(true)) return
        stop()
        audioTrack?.release()
        audioTrack = null
        ringBuffer = null
        Log.d(TAG, "$purpose released")
    }

    val isActive: Boolean get() = active.get()
    val ringBufferAvailable: Int get() = ringBuffer?.available ?: 0
    val ringBufferCapacity: Int get() = ringBuffer?.capacity ?: 0

    /**
     * Drain loop — BLOCKING writes, NO sleep, NO non-blocking.
     *
     * Identical to how stagefright plays audio (zero HAL errors on this emulator):
     * tight loop of read → blocking write. AudioTrack.write() blocks for exactly
     * the right duration (~10ms per 1920-byte chunk at 48kHz stereo), providing
     * perfect real-time pacing without any Thread.sleep() imprecision.
     *
     * The ring buffer decouples TCP arrival jitter from AudioTrack consumption.
     * The drain thread runs at URGENT_AUDIO priority with nothing else to do.
     */
    private fun drainLoop() {
        val track = audioTrack ?: return
        val ring = ringBuffer ?: return
        val bytesPerFrame = channelCount * 2
        val bytesPerMs = sampleRate * bytesPerFrame / 1000
        val prefillBytes = bytesPerMs * PREFILL_MS
        val chunkBytes = bytesPerMs * DRAIN_CHUNK_MS
        val chunk = ByteArray(chunkBytes)
        var writeCount = 0L

        while (active.get()) {
            // Pre-fill: accumulate data before starting playback
            if (!trackPlaying) {
                if (ring.available >= prefillBytes) {
                    track.play()
                    trackPlaying = true
                    Log.d(TAG, "$purpose prefill done, playing")
                } else {
                    Thread.sleep(5)
                    continue
                }
            }

            val avail = ring.available
            if (avail >= chunkBytes) {
                val read = ring.read(chunk)
                if (read > 0) {
                    val t0 = System.nanoTime()
                    track.write(chunk, 0, read)
                    val elapsed = (System.nanoTime() - t0) / 1_000_000
                    framesWritten.addAndGet(read.toLong() / bytesPerFrame)
                    writeCount++
                    if (elapsed > 50 || writeCount % 500 == 0L) {
                        Log.w(TAG, "$purpose write: ${elapsed}ms for ${read}B (total writes=$writeCount)")
                    }
                }
                // NO sleep — AudioTrack.write() already blocked for ~10ms
            } else if (avail > 0) {
                // Partial data — write what we have
                val partial = ByteArray(avail)
                val read = ring.read(partial)
                if (read > 0) {
                    track.write(partial, 0, read)
                    framesWritten.addAndGet(read.toLong() / bytesPerFrame)
                }
            } else {
                // Ring empty — brief wait for TCP data
                underrunCount.incrementAndGet()
                Thread.sleep(2)
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
