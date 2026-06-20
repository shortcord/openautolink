package com.openautolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.openautolink.app.transport.AudioPurpose
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One AudioTrack per audio purpose, with a dedicated write thread
 * for non-blocking PCM output and AAC decode-on-demand.
 */
class AudioPurposeSlot(
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channelCount: Int,
    private val bufferDurationMs: Int = 500
) {
    companion object {
        private const val TAG = "AudioPurposeSlot"
    }

    private var audioTrack: AudioTrack? = null
    private var aacDecoder: AacDecoder? = null

    /** Per-purpose write thread — isolates blocking AudioTrack.write() calls
     *  so one purpose stalling doesn't block others. */
    private var writeExecutor: ExecutorService? = null

    private val active = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val pausedByFocusLoss = AtomicBoolean(false)

    val framesWritten = AtomicLong(0)
    val underrunCount = AtomicLong(0)

    // Diagnostic counters
    @Volatile var startedAtNs: Long = 0L
    @Volatile var lastFeedTimeNs: Long = 0L
    @Volatile var maxWriteMs: Long = 0L
    @Volatile var maxGapMs: Long = 0L
    @Volatile var totalWriteCalls: Long = 0L
    @Volatile var slowWriteCount: Long = 0L  // writes > 60ms
    @Volatile var hwUnderrunCount: Long = 0L

    fun initialize() {
        if (released.get()) return

        val channelMask = if (channelCount == 2)
            AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        // Use 4x min buffer — same as app_v1 production
        val trackBufSize = maxOf(minBufSize * 4, 16384)

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

        Log.d(TAG, "Initialized $purpose: ${sampleRate}Hz ${channelCount}ch, track=${trackBufSize}B")
    }

    fun start() {
        if (released.get() || active.get()) return
        val track = audioTrack ?: return
        active.set(true)
        startedAtNs = System.nanoTime()

        // Create per-purpose write thread with URGENT_AUDIO priority.
        // Priority is set at thread creation time (not lazily on first feedPcm)
        // so every write runs at the correct priority from the start.
        writeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AudioWrite-$purpose").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                // Also set the Android-specific audio thread priority
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                    )
                } catch (_: Exception) {}
            }
        }

        track.play()
        Log.d(TAG, "$purpose started (per-purpose write thread)")
    }

    fun stop() {
        pausedByFocusLoss.set(false)
        if (!active.getAndSet(false)) return
        startedAtNs = 0L
        lastFeedTimeNs = 0L
        writeExecutor?.shutdown()
        writeExecutor = null
        audioTrack?.pause()
        audioTrack?.flush()
        Log.d(TAG, "$purpose stopped")
    }

    fun pause() {
        if (!active.getAndSet(false)) return
        pausedByFocusLoss.set(true)
        audioTrack?.pause()
        Log.d(TAG, "$purpose paused")
    }

    fun resume() {
        if (released.get() || active.get()) return
        if (!pausedByFocusLoss.getAndSet(false)) return
        val track = audioTrack ?: return
        active.set(true)
        track.play()
        Log.d(TAG, "$purpose resumed")
    }

    val isPausedByFocus: Boolean get() = pausedByFocusLoss.get()

    /**
     * Submit PCM to per-purpose write thread. Non-blocking for the caller —
     * the blocking AudioTrack.write() runs on this slot's own thread,
     * so one purpose stalling doesn't block others.
     *
     * Double-checks [active] inside the executor to handle the race where
     * [stop] is called between the outer check and the executor executing.
     */
    fun feedPcm(data: ByteArray) {
        if (!active.get()) return
        val executor = writeExecutor ?: return

        executor.execute {
            // Re-check active inside the executor thread — [stop] may have
            // fired between the outer check and this runnable executing.
            if (!active.get()) return@execute
            val track = audioTrack ?: return@execute

            // Measure inter-frame gap
            val nowNs = System.nanoTime()
            val prevNs = lastFeedTimeNs
            lastFeedTimeNs = nowNs
            if (prevNs > 0) {
                val gapMs = (nowNs - prevNs) / 1_000_000
                if (gapMs > maxGapMs) maxGapMs = gapMs
            }

            // Measure AudioTrack.write() blocking duration
            val writeStartNs = System.nanoTime()
            track.write(data, 0, data.size) // blocking — only blocks THIS purpose's thread
            val writeMs = (System.nanoTime() - writeStartNs) / 1_000_000

            totalWriteCalls++
            framesWritten.addAndGet(data.size.toLong() / (channelCount * 2))
            if (writeMs > maxWriteMs) maxWriteMs = writeMs
            if (writeMs > 60) slowWriteCount++

            // Sample HW underrun count periodically
            if (totalWriteCalls % 50 == 0L) {
                try {
                    hwUnderrunCount = track.underrunCount.toLong()
                } catch (_: Exception) {}
            }
        }
    }

    fun setVolume(volume: Float) {
        // Volume may exceed 1.0 from coordinator gain (user offset up to +100 → 2.0x)
        audioTrack?.setVolume(volume.coerceIn(0f, 2f))
    }

    /**
     * Feed an AAC-LC encoded frame. Creates an AacDecoder on first call
     * which decodes to PCM and routes through feedPcm().
     */
    fun feedAac(data: ByteArray) {
        if (!active.get()) return
        var decoder = aacDecoder
        if (decoder == null) {
            decoder = AacDecoder(sampleRate, channelCount) { pcm -> feedPcm(pcm) }
            aacDecoder = decoder
            decoder.start()
            Log.i(TAG, "$purpose: AAC decoder started (${sampleRate}Hz ${channelCount}ch)")
        }
        decoder.queueAacFrame(data)
    }

    fun release() {
        if (released.getAndSet(true)) return
        stop()
        aacDecoder?.stop()
        aacDecoder = null
        writeExecutor?.shutdownNow()
        writeExecutor = null
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "$purpose released")
    }

    val isActive: Boolean get() = active.get()

    /**
     * How long this slot has been idle (no frames received) in ms.
     * Returns -1 if the slot is not active.
     */
    fun idleMs(): Long {
        if (!active.get()) return -1
        val now = System.nanoTime()
        val lastFeed = lastFeedTimeNs
        if (lastFeed > 0) return (now - lastFeed) / 1_000_000
        // Never received a frame — use start time
        val started = startedAtNs
        return if (started > 0) (now - started) / 1_000_000 else -1
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