package com.openautolink.app.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * A single local log entry captured by [DiagnosticLog].
 */
data class LocalLogEntry(
    val timestamp: Long,
    val level: DiagnosticLevel,
    val tag: String,
    val message: String,
)

/**
 * Global diagnostic logger — subsystems call this to emit diagnostic log events.
 *
 * This is a thin facade over [RemoteDiagnostics] that allows any subsystem
 * to log without holding a direct reference to the diagnostics instance.
 * The session manager sets the active instance; when null, calls are no-ops.
 *
 * Local buffering is **opt-in** — call [startLocalCapture]/[stopLocalCapture]
 * from the diagnostics UI. When capture is off, log calls only forward to
 * the remote instance with zero local allocation. When on, entries are kept
 * in a fixed-capacity ring buffer (200 entries, ~200 KB worst case) and
 * snapshotted to a StateFlow for UI collection.
 *
 * Thread-safe: synchronized ring buffer, volatile flags.
 */
object DiagnosticLog {

    private const val MAX_LOCAL_ENTRIES = 200

    @Volatile
    var instance: RemoteDiagnostics? = null

    @Volatile
    private var localCaptureActive = false

    private val ring = ArrayDeque<LocalLogEntry>(MAX_LOCAL_ENTRIES)
    private val _localLogs = MutableStateFlow<List<LocalLogEntry>>(emptyList())
    val localLogs: StateFlow<List<LocalLogEntry>> = _localLogs.asStateFlow()

    /** Start capturing log entries locally. Called when diagnostics screen opens. */
    fun startLocalCapture() {
        localCaptureActive = true
    }

    /** Stop capturing and release the buffer. Called when diagnostics screen closes. */
    fun stopLocalCapture() {
        localCaptureActive = false
        synchronized(ring) { ring.clear() }
        _localLogs.value = emptyList()
    }

    fun d(tag: String, msg: String) {
        if (localCaptureActive) addLocal(DiagnosticLevel.DEBUG, tag, msg)
        instance?.log(DiagnosticLevel.DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (localCaptureActive) addLocal(DiagnosticLevel.INFO, tag, msg)
        instance?.log(DiagnosticLevel.INFO, tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (localCaptureActive) addLocal(DiagnosticLevel.WARN, tag, msg)
        instance?.log(DiagnosticLevel.WARN, tag, msg)
    }

    fun e(tag: String, msg: String) {
        if (localCaptureActive) addLocal(DiagnosticLevel.ERROR, tag, msg)
        instance?.log(DiagnosticLevel.ERROR, tag, msg)
    }

    fun clearLocal() {
        synchronized(ring) { ring.clear() }
        _localLogs.value = emptyList()
    }

    private fun addLocal(level: DiagnosticLevel, tag: String, msg: String) {
        val entry = LocalLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = if (msg.length > 500) msg.take(500) else msg,
        )
        synchronized(ring) {
            if (ring.size >= MAX_LOCAL_ENTRIES) ring.pollFirst()
            ring.addLast(entry)
            _localLogs.value = ring.toList()
        }
    }
}
