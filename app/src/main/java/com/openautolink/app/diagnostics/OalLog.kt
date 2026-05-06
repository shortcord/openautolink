package com.openautolink.app.diagnostics

import android.util.Log

/**
 * Version-prefixed Android Log wrapper.
 *
 * Every log line includes the app version so you never have to guess
 * which build is running when reading logcat output.
 *
 * Usage:
 *   OalLog.i(TAG, "Connected to bridge")
 *   // Output: I/ConnectionManager: [app 0.1.114] Connected to bridge
 */
object OalLog {
    private var _prefix: String = "[app ?] "

    /**
     * When false (default), DEBUG/VERBOSE entries are dropped from the
     * Kotlin diagnostic pipeline (ring buffer, file writer, remote sinks)
     * and only land in Android logcat. Flip via [setVerbose] from a
     * settings toggle when investigating an issue.
     *
     * INFO/WARN/ERROR are always forwarded.
     */
    @Volatile
    private var verbose: Boolean = false

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
        DiagnosticLog.setVerbose(enabled)
    }

    fun isVerbose(): Boolean = verbose

    /** Call once from Application.onCreate() or Activity.onCreate(). */
    fun init(versionName: String) {
        _prefix = "[app $versionName] "
    }

    fun v(tag: String, msg: String) { Log.v(tag, _prefix + msg) }
    fun d(tag: String, msg: String) {
        Log.d(tag, _prefix + msg)
        if (verbose) DiagnosticLog.d(tag, msg)
    }
    fun i(tag: String, msg: String) { Log.i(tag, _prefix + msg); DiagnosticLog.i(tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, _prefix + msg); DiagnosticLog.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) { Log.w(tag, _prefix + msg, tr); DiagnosticLog.w(tag, "$msg: ${tr.message}") }
    fun e(tag: String, msg: String) { Log.e(tag, _prefix + msg); DiagnosticLog.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { Log.e(tag, _prefix + msg, tr); DiagnosticLog.e(tag, "$msg: ${tr.message}") }
}
