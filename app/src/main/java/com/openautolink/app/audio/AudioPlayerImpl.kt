package com.openautolink.app.audio

import android.media.AudioManager
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.transport.AudioPurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Timer
import java.util.TimerTask

/**
 * 5-purpose AudioTrack management — routes incoming audio frames to
 * pre-allocated per-purpose slots with cross-purpose ducking.
 *
 * Both AA session audio (media, navigation, alerts) and BT HFP audio
 * (phone calls, voice assistant) arrive as OAL PCM frames with different
 * purpose values. This class handles both paths uniformly.
 *
 * Default sample rates/channels per protocol spec:
 *   MEDIA:      48000 Hz, Stereo
 *   NAVIGATION: 16000 Hz, Mono
 *   ASSISTANT:  16000 Hz, Mono
 *   PHONE_CALL:  8000 Hz, Mono
 *   ALERT:      24000 Hz, Mono
 *
 * Slots are pre-allocated at initialize() and reused. The bridge sends
 * audio_start with actual sample_rate/channels before streaming begins,
 * and the slot is recreated only if the format changes.
 */
class AudioPlayerImpl(private val audioManager: AudioManager) : AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayerImpl"

        /** Stop a purpose after this many ms with no audio frames.
         *  Nav/assistant/alert are bursty — use shorter timeout.
         *  Media can have brief pauses between tracks — use longer timeout. */
        private const val IDLE_TIMEOUT_MEDIA_MS = 5000L
        private const val IDLE_TIMEOUT_OTHER_MS = 2000L

        /** How often to check for idle purposes. */
        private const val IDLE_CHECK_INTERVAL_MS = 2000L

        /** Suppress auto-start for this long after an explicit stopPurpose(). */
        private const val AUTO_START_SUPPRESS_MS = 1000L

        /** Default formats per purpose — used until bridge sends audio_start. */
        private val DEFAULT_FORMATS = mapOf(
            AudioPurpose.MEDIA to AudioFormat(48000, 2),
            AudioPurpose.NAVIGATION to AudioFormat(16000, 1),
            AudioPurpose.ASSISTANT to AudioFormat(16000, 1),
            AudioPurpose.PHONE_CALL to AudioFormat(8000, 1),
            AudioPurpose.ALERT to AudioFormat(24000, 1)
        )
    }

    private data class AudioFormat(val sampleRate: Int, val channels: Int)

    private val slots = mutableMapOf<AudioPurpose, AudioPurposeSlot>()
    private val slotFormats = mutableMapOf<AudioPurpose, AudioFormat>()
    private val focusManager = AudioFocusManager(audioManager)
    val coordinator = AudioPurposeCoordinator()
    private var idleCheckTimer: Timer? = null

    /** Tracks when each purpose was explicitly stopped via stopPurpose().
     *  Auto-start is suppressed for AUTO_START_SUPPRESS_MS after an explicit stop
     *  to prevent straggler audio frames (arriving on a different TCP channel
     *  after the audio_stop control message) from immediately reactivating. */
    private val explicitStopTimes = mutableMapOf<AudioPurpose, Long>()

    private val _stats = MutableStateFlow(AudioStats())
    override val stats: StateFlow<AudioStats> = _stats.asStateFlow()

    override val callState: StateFlow<CallState> = coordinator.callState

    private var initialized = false

    override fun initialize() {
        if (initialized) return

        // Pre-allocate all 5 AudioTrack slots with default formats
        for (purpose in AudioPurpose.entries) {
            val fmt = DEFAULT_FORMATS[purpose] ?: AudioFormat(48000, 2)
            val slot = AudioPurposeSlot(purpose, fmt.sampleRate, fmt.channels)
            slot.initialize()
            slots[purpose] = slot
            slotFormats[purpose] = fmt
        }

        // Request session-level audio focus once
        focusManager.requestSessionFocus(
            onLost = { pauseAllActive() },
            onRegained = { resumeAllPaused() }
        )

        initialized = true
        Log.i(TAG, "Audio player initialized with ${slots.size} purpose slots")
        DiagnosticLog.i("audio", "AudioPlayer initialized with ${slots.size} purpose slots")
        startIdleChecker()
        updateStats()
    }

    override fun release() {
        if (!initialized) return

        stopIdleChecker()
        for ((_, slot) in slots) {
            slot.release()
        }
        slots.clear()
        slotFormats.clear()
        explicitStopTimes.clear()
        focusManager.releaseFocus()
        coordinator.reset()
        initialized = false

        Log.i(TAG, "Audio player released")
        _stats.value = AudioStats()
    }

    private var audioFrameCount = 0L
    private var audioBytesReceived = 0L
    private var lastAudioLogTime = 0L

    override fun onAudioFrame(frame: AudioFrame) {
        if (!frame.isPlayback) return

        val slot = slots[frame.purpose]
        if (slot == null) {
            Log.w(TAG, "No slot for purpose ${frame.purpose}")
            DiagnosticLog.w("audio", "No slot for purpose ${frame.purpose}")
            return
        }

        // Auto-start: if audio frames arrive before audio_start control message
        // (happens when phone was already streaming before app connected),
        // start the slot with the frame's sample rate and channel count.
        // BUT: suppress auto-start briefly after an explicit stop to prevent
        // straggler frames from reactivating a purpose that was just stopped.
        if (!slot.isActive) {
            val suppressUntil = explicitStopTimes[frame.purpose] ?: 0L
            if (System.currentTimeMillis() - suppressUntil < AUTO_START_SUPPRESS_MS) {
                return // straggler frame after explicit stop — discard
            }
            Log.i(TAG, "Auto-starting ${frame.purpose} from audio frame: ${frame.sampleRate}Hz ${frame.channels}ch")
            startPurpose(frame.purpose, frame.sampleRate, frame.channels)
        }

        audioFrameCount++
        audioBytesReceived += frame.data.size
        val now = System.currentTimeMillis()
        if (now - lastAudioLogTime >= 2000) {
            Log.i(TAG, "audio: frames=$audioFrameCount bytes=$audioBytesReceived " +
                    "thisFrame=${frame.data.size}B " +
                    "written=${slot.framesWritten.get()} underruns=${slot.underrunCount.get()}")
            // Detailed per-slot diagnostics for remote viewing
            for ((p, s) in slots) {
                if (!s.isActive && s.totalWriteCalls == 0L) continue
                DiagnosticLog.d("audio", "slot=$p active=${s.isActive} " +
                        "writes=${s.totalWriteCalls} written=${s.framesWritten.get()} " +
                        "maxWriteMs=${s.maxWriteMs} slowWrites=${s.slowWriteCount} " +
                        "maxGapMs=${s.maxGapMs} hwUnderruns=${s.hwUnderrunCount}")
            }
            lastAudioLogTime = now
        }

        if (frame.isAac) {
            slot.feedAac(frame.data)
        } else {
            slot.feedPcm(frame.data)
        }
    }

    override fun startPurpose(purpose: AudioPurpose, sampleRate: Int, channels: Int) {
        val existingSlot = slots[purpose]
        val requestedFmt = AudioFormat(sampleRate, channels)
        explicitStopTimes.remove(purpose)  // clear suppression on explicit start

        // Check if format changed from what the slot was created with — recreate if so
        if (existingSlot != null) {
            val currentFmt = slotFormats[purpose]
            if (currentFmt != requestedFmt && !existingSlot.isActive) {
                Log.i(TAG, "Recreating $purpose slot: ${sampleRate}Hz ${channels}ch")
                existingSlot.release()
                val newSlot = AudioPurposeSlot(purpose, sampleRate, channels)
                newSlot.initialize()
                slots[purpose] = newSlot
                slotFormats[purpose] = requestedFmt
            }
        }

        val slot = slots[purpose] ?: return
        slot.start()

        // Notify coordinator and apply volume ducking
        val actions = coordinator.onPurposeStarted(purpose)
        applyVolumeActions(actions)

        Log.i(TAG, "Started $purpose: ${sampleRate}Hz ${channels}ch (call=${coordinator.callState.value})")
        DiagnosticLog.i("audio", "AudioTrack started: purpose=$purpose, rate=${sampleRate}, ch=$channels")
        updateStats()
    }

    override fun stopPurpose(purpose: AudioPurpose) {
        val slot = slots[purpose] ?: return
        slot.stop()
        explicitStopTimes[purpose] = System.currentTimeMillis()

        // Notify coordinator and restore volumes
        val actions = coordinator.onPurposeStopped(purpose)
        applyVolumeActions(actions)

        Log.i(TAG, "Stopped $purpose (call=${coordinator.callState.value})")
        DiagnosticLog.i("audio", "AudioTrack stopped: purpose=$purpose")
        updateStats()
    }

    override fun stopAll() {
        var changed = false
        val now = System.currentTimeMillis()
        for ((purpose, slot) in slots) {
            if (!slot.isActive) continue
            slot.stop()
            explicitStopTimes[purpose] = now
            coordinator.onPurposeStopped(purpose)
            changed = true
        }
        if (changed) {
            Log.i(TAG, "Stopped all active audio purposes")
            DiagnosticLog.i("audio", "Stopped all active audio purposes")
            updateStats()
        }
    }

    override fun setVolume(purpose: AudioPurpose, volume: Float) {
        slots[purpose]?.setVolume(volume)
    }

    /**
     * Apply a batch of volume actions from the coordinator.
     * Sets each active purpose's AudioTrack volume.
     */
    private fun applyVolumeActions(actions: List<VolumeAction>) {
        for (action in actions) {
            val slot = slots[action.purpose] ?: continue
            slot.setVolume(action.volume)
            if (action.volume < AudioPurposeCoordinator.NORMAL_VOLUME) {
                Log.d(TAG, "Ducked ${action.purpose} to ${action.volume}")
            }
        }
    }

    /**
     * Pause all active slots on external audio focus loss.
     * Does NOT clear ring buffers — overflow drops oldest naturally.
     */
    private fun pauseAllActive() {
        for ((purpose, slot) in slots) {
            if (slot.isActive) {
                slot.pause()
                Log.d(TAG, "Paused $purpose (focus loss)")
            }
        }
        updateStats()
    }

    /**
     * Resume all previously-active slots on external audio focus regain.
     * Slots that were explicitly stopped (not paused) won't resume.
     */
    private fun resumeAllPaused() {
        for ((purpose, slot) in slots) {
            if (!slot.isActive && slot.isPausedByFocus) {
                slot.resume()
                if (slot.isActive) {
                    Log.d(TAG, "Resumed $purpose (focus regain)")
                }
            }
        }
        // Re-apply coordinator volumes after resuming
        val actions = AudioPurpose.entries
            .filter { slots[it]?.isActive == true }
            .flatMap { coordinator.onPurposeStarted(it) }
        applyVolumeActions(actions)
        updateStats()
    }

    private fun updateStats() {
        val prev = _stats.value
        val active = mutableSetOf<AudioPurpose>()
        val underruns = mutableMapOf<AudioPurpose, Long>()
        val written = mutableMapOf<AudioPurpose, Long>()
        val maxWrite = mutableMapOf<AudioPurpose, Long>()
        val slowW = mutableMapOf<AudioPurpose, Long>()
        val maxGap = mutableMapOf<AudioPurpose, Long>()
        val hwUr = mutableMapOf<AudioPurpose, Long>()

        for ((purpose, slot) in slots) {
            if (slot.isActive) active.add(purpose)
            val u = slot.underrunCount.get()
            if (u > 0) {
                underruns[purpose] = u
                val prevU = prev.underruns[purpose] ?: 0L
                if (u > prevU) {
                    DiagnosticLog.w("audio", "Underrun on $purpose: $u total (+${u - prevU})")
                }
            }
            val fw = slot.framesWritten.get()
            if (fw > 0) written[purpose] = fw
            if (slot.totalWriteCalls > 0) {
                maxWrite[purpose] = slot.maxWriteMs
                maxGap[purpose] = slot.maxGapMs
            }
            if (slot.slowWriteCount > 0) slowW[purpose] = slot.slowWriteCount
            if (slot.hwUnderrunCount > 0) hwUr[purpose] = slot.hwUnderrunCount
        }

        _stats.value = AudioStats(
            activePurposes = active,
            underruns = underruns,
            framesWritten = written,
            maxWriteMs = maxWrite,
            slowWrites = slowW,
            maxGapMs = maxGap,
            hwUnderruns = hwUr,
        )
    }

    private fun startIdleChecker() {
        idleCheckTimer = Timer("audio-idle-check", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    checkIdlePurposes()
                }
            }, IDLE_CHECK_INTERVAL_MS, IDLE_CHECK_INTERVAL_MS)
        }
    }

    private fun stopIdleChecker() {
        idleCheckTimer?.cancel()
        idleCheckTimer = null
    }

    private fun checkIdlePurposes() {
        var changed = false
        for ((purpose, slot) in slots) {
            if (!slot.isActive) continue
            val idle = slot.idleMs()
            val timeout = if (purpose == AudioPurpose.MEDIA) IDLE_TIMEOUT_MEDIA_MS else IDLE_TIMEOUT_OTHER_MS
            if (idle >= timeout) {
                slot.stop()
                coordinator.onPurposeStopped(purpose)
                Log.i(TAG, "Auto-stopped idle $purpose (idle ${idle}ms)")
                DiagnosticLog.i("audio", "Auto-stopped idle $purpose (${idle}ms)")
                changed = true
            }
        }
        if (changed) {
            updateStats()
        }
    }
}
