package com.openautolink.app.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AAC-LC decoder using MediaCodec. Decodes AAC frames to PCM for AudioTrack playback.
 *
 * Uses synchronous mode on a dedicated thread for simplicity and low latency.
 * The phone sends raw AAC-LC frames (no ADTS headers) on audio channels.
 */
class AacDecoder(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val onPcmDecoded: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "AacDecoder"
        private const val MIME = "audio/mp4a-latm"
        private const val MAX_INPUT_SIZE = 16384
        private const val DEQUEUE_TIMEOUT_US = 10_000L // 10ms
    }

    private var codec: MediaCodec? = null
    private val inputQueue = LinkedBlockingQueue<ByteArray>(256)
    @Volatile private var running = false
    private var decodeThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setByteBuffer("csd-0", ByteBuffer.wrap(makeAacCsd(sampleRate, channelCount)))
        }

        try {
            codec = MediaCodec.createDecoderByType(MIME).also { mc ->
                mc.configure(format, null, null, 0)
                mc.start()
            }
            Log.i(TAG, "AAC decoder started: ${sampleRate}Hz ${channelCount}ch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AAC decoder: ${e.message}")
            running = false
            return
        }

        decodeThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            decodeLoop()
        }, "AacDecoder-$sampleRate").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        inputQueue.clear()
        decodeThread?.interrupt()
        decodeThread = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }

    /**
     * Queue an AAC frame for decoding. Non-blocking.
     * When the queue is full, drops the oldest frame to make room — keeps the
     * decoder fed with the most recent data rather than blocking the caller.
     */
    fun queueAacFrame(data: ByteArray) {
        if (!inputQueue.offer(data)) {
            inputQueue.poll() // drop oldest
            inputQueue.offer(data)
        }
    }

    private fun decodeLoop() {
        val mc = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            // Feed input — poll with a short timeout so we also service
            // the output drain regularly when no input is arriving.
            val aacFrame = inputQueue.poll(5, TimeUnit.MILLISECONDS)
            if (aacFrame != null) {
                val inputIndex = mc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = mc.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    inputBuffer.put(aacFrame)
                    mc.queueInputBuffer(inputIndex, 0, aacFrame.size, 0, 0)
                }
            }

            // Drain all available output — dequeueOutputBuffer with 0 timeout
            // since we already waited above. This avoids unnecessary spinning
            // when no output is ready.
            while (running) {
                val outputIndex = mc.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex >= 0) {
                    val outputBuffer = mc.getOutputBuffer(outputIndex) ?: break
                    if (bufferInfo.size > 0) {
                        val pcm = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(pcm)
                        onPcmDecoded(pcm)
                    }
                    mc.releaseOutputBuffer(outputIndex, false)
                } else {
                    break // INFO_TRY_AGAIN_LATER — no more output available right now
                }
            }
        }
    }

    /** Generate AAC AudioSpecificConfig (2 bytes) for CSD-0. */
    private fun makeAacCsd(sampleRate: Int, channelCount: Int): ByteArray {
        val freqIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
        val aot = 2 // AAC-LC
        val config = (aot shl 11) or (freqIndex shl 7) or (channelCount shl 3)
        return byteArrayOf(
            ((config shr 8) and 0xFF).toByte(),
            (config and 0xFF).toByte()
        )
    }
}
